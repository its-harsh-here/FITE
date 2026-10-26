package me.desair.spring.transfer;

import me.desair.spring.transfer.application.TransferService;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import me.desair.spring.transfer.infrastructure.storage.ChunkStorage;
import me.desair.spring.transfer.infrastructure.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import me.desair.spring.transfer.domain.TransferStatus;
import me.desair.spring.transfer.domain.TransferDomainException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UploadProtocolTest {

    @Mock
    private TransferRepository transferRepository;
    
    @Mock
    private TransferChunkRepository chunkRepository;

    @Mock
    private ChunkStorage chunkStorage;

    private TransferService service;

    private TransferEntity dummyTransfer;

    @BeforeEach
    void setUp() {
        service = new TransferService(transferRepository, chunkRepository, chunkStorage, 8388608L);
        dummyTransfer = new TransferEntity();
        dummyTransfer.setTransferId("tf_1");
        dummyTransfer.setShareToken("st_1");
        dummyTransfer.setFileName("test.txt");
        dummyTransfer.setFileSize(250);
        dummyTransfer.setChunkSize(100); // chunks: 0(100), 1(100), 2(50)
        dummyTransfer.setTotalChunks(3);
        dummyTransfer.setStatus(TransferStatus.CREATED);
        dummyTransfer.setCreatedAt(Instant.now());
        dummyTransfer.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
    }

    private byte[] createData(int size, byte fill) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = fill;
        return data;
    }

    private String calculateSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    @Test
    void testNormalUpload() throws Exception {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        byte[] data = createData(100, (byte) 1);
        
        service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 100);
        
        verify(chunkStorage).putChunk(eq("tf_1"), eq(0), anyString(), any(InputStream.class), eq(100L));
        verify(chunkRepository).saveAndFlush(any(TransferChunkEntity.class));
    }

    @Test
    void testInvalidIndex() {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        byte[] data = createData(100, (byte) 1);
        
        assertThrows(TransferDomainException.class, () -> 
            service.uploadChunk("tf_1", 5, null, new ByteArrayInputStream(data), 100));
    }

    @Test
    void testWrongSize() {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        // Chunk 0 expects 100 bytes. We send 99.
        byte[] data = createData(99, (byte) 1);
        
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> 
            service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 99));
        assertTrue(e.getMessage().contains("Invalid chunk size"));
        
        verifyNoInteractions(chunkStorage);
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void testChecksumMismatch() throws Exception {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        byte[] data = createData(100, (byte) 1);
        String wrongHash = "0000000000000000000000000000000000000000000000000000000000000000";
        
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> 
            service.uploadChunk("tf_1", 0, wrongHash, new ByteArrayInputStream(data), 100));
        assertTrue(e.getMessage().contains("Checksum mismatch"));
        
        verifyNoInteractions(chunkStorage);
        verify(chunkRepository, never()).save(any());
        verify(chunkRepository, never()).saveAndFlush(any());
    }

    @Test
    void testIdenticalRetryIsIdempotent() throws Exception {
        byte[] data = createData(100, (byte) 1);
        String hash = calculateSha256(data);
        
        TransferChunkEntity existing = new TransferChunkEntity();
        existing.setChunkIndex(0);
        existing.setSize(100);
        existing.setChecksum(hash);
        
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_1")).thenReturn(java.util.List.of(existing));
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_1", 0)).thenReturn(Optional.of(existing));
        
        // Upload again with exactly the same bytes
        service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 100);
        
        // Should not hit storage or DB save because it's completely identical
        verifyNoInteractions(chunkStorage);
        verify(chunkRepository, never()).save(any());
        verify(chunkRepository, never()).saveAndFlush(any());
    }

    @Test
    void testConflictingRetry() throws Exception {
        byte[] data1 = createData(100, (byte) 1);
        String hash1 = calculateSha256(data1);
        
        TransferChunkEntity existing = new TransferChunkEntity();
        existing.setChunkIndex(0);
        existing.setSize(100);
        existing.setChecksum(hash1); // Hash of 1s
        
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_1")).thenReturn(java.util.List.of(existing));
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_1", 0)).thenReturn(Optional.of(existing));
        
        // Upload with different bytes (2s)
        byte[] data2 = createData(100, (byte) 2);
        
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> 
            service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data2), 100));
        assertTrue(e.getMessage().contains("Chunk already exists with different content"));
        
        verifyNoInteractions(chunkStorage);
        verify(chunkRepository, never()).saveAndFlush(any());
    }

    @Test
    void testStorageFailurePreventsDbUpdate() throws Exception {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        byte[] data = createData(100, (byte) 1);
        
        doThrow(new StorageException("Disk full")).when(chunkStorage).putChunk(anyString(), anyInt(), anyString(), any(InputStream.class), anyLong());
        
        assertThrows(StorageException.class, () -> 
            service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 100));
            
        verify(chunkRepository, never()).save(any());
        verify(chunkRepository, never()).saveAndFlush(any());
    }

    @Test
    void testDbFailureThrows() throws Exception {
        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        byte[] data = createData(100, (byte) 1);
        
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_1", 0)).thenReturn(Optional.empty());
        when(chunkRepository.saveAndFlush(any())).thenThrow(new RuntimeException("DB down"));
        
        assertThrows(RuntimeException.class, () -> 
            service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 100));
    }

    @Test
    void testConcurrentDuplicateInsertIdempotentResolution() throws Exception {
        byte[] data = createData(100, (byte) 1);
        String hash = calculateSha256(data);

        TransferChunkEntity winningChunk = new TransferChunkEntity();
        winningChunk.setChunkIndex(0);
        winningChunk.setSize(100);
        winningChunk.setChecksum(hash);

        when(transferRepository.findById("tf_1")).thenReturn(Optional.of(dummyTransfer));
        // First check returns empty (not in DB yet)
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_1", 0))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winningChunk)); // Second check after race returns winning chunk
        // DB insert throws unique constraint violation because concurrent thread inserted first
        when(chunkRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique constraint"));

        // Should resolve idempotently without throwing
        service.uploadChunk("tf_1", 0, null, new ByteArrayInputStream(data), 100);
    }
}
