package me.desair.spring.transfer;

import me.desair.spring.transfer.api.ChunkDownloadUrlResponse;
import me.desair.spring.transfer.api.ChunkUploadUrlResponse;
import me.desair.spring.transfer.application.TransferService;
import me.desair.spring.transfer.domain.TransferStatus;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import me.desair.spring.transfer.infrastructure.storage.B2ChunkStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DirectTransferIntegrationTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferChunkRepository chunkRepository;

    @Mock
    private B2ChunkStorage b2ChunkStorage;

    private TransferService transferService;
    private TransferEntity dummyTransfer;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, chunkRepository, b2ChunkStorage, 8388608L);

        dummyTransfer = new TransferEntity();
        dummyTransfer.setTransferId("tf_123");
        dummyTransfer.setShareToken("st_123");
        dummyTransfer.setFileName("test.pdf");
        dummyTransfer.setFileSize(10000000L);
        dummyTransfer.setChunkSize(8388608L);
        dummyTransfer.setTotalChunks(2);
        dummyTransfer.setStatus(TransferStatus.CREATED);
        dummyTransfer.setCreatedAt(Instant.now());
        dummyTransfer.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
    }

    private TransferChunkEntity createChunkEntity(String transferId, int chunkIndex, String checksum, long size, String storageKey) {
        TransferChunkEntity chunk = new TransferChunkEntity();
        chunk.setTransferId(transferId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChecksum(checksum);
        chunk.setSize(size);
        chunk.setStorageKey(storageKey);
        chunk.setUploadedAt(Instant.now());
        return chunk;
    }

    @Test
    void testDirectUploadUrlAndCommitFlow() {
        String transferId = dummyTransfer.getTransferId();
        String validChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String md5 = "Q2hlY2tzdW0=";
        long expectedSize = 8388608L;

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(dummyTransfer));
        when(b2ChunkStorage.generateUploadPresignedUrl(eq(transferId), eq(0), eq(validChecksum), eq(md5), eq(expectedSize), any(Duration.class)))
                .thenReturn(new ChunkUploadUrlResponse("https://s3.example.com/put-0", "key-0", Map.of("Content-MD5", md5), Instant.now().plusSeconds(900)));

        ChunkUploadUrlResponse uploadUrlResp = transferService.getChunkUploadUrl(transferId, 0, validChecksum, md5, expectedSize);
        assertNotNull(uploadUrlResp);
        assertEquals("https://s3.example.com/put-0", uploadUrlResp.getUploadUrl());

        when(b2ChunkStorage.verifyChunkObject(eq(transferId), eq(0), eq(validChecksum), eq(md5), eq(expectedSize)))
                .thenReturn(true);
        when(chunkRepository.findByTransferIdAndChunkIndex(transferId, 0)).thenReturn(Optional.empty());
        when(chunkRepository.saveAndFlush(any(TransferChunkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TransferChunkEntity committedChunk = transferService.commitChunk(transferId, 0, validChecksum, md5, expectedSize);
        assertNotNull(committedChunk);
        assertEquals(0, committedChunk.getChunkIndex());
        assertEquals(validChecksum, committedChunk.getChecksum());
    }

    @Test
    void testCommitRejectsWhenB2VerificationFails() {
        String transferId = dummyTransfer.getTransferId();
        String validChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(dummyTransfer));
        when(b2ChunkStorage.verifyChunkObject(anyString(), anyInt(), anyString(), any(), anyLong()))
                .thenReturn(false);

        assertThrows(IllegalArgumentException.class, () ->
                transferService.commitChunk(transferId, 0, validChecksum, "bad_md5", 8388608L));
    }

    @Test
    void testCommitIdempotency() {
        String transferId = dummyTransfer.getTransferId();
        String validChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        TransferChunkEntity existing = createChunkEntity(transferId, 0, validChecksum, 8388608L, "key-0");
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(dummyTransfer));
        when(chunkRepository.findByTransferIdAndChunkIndex(transferId, 0)).thenReturn(Optional.of(existing));

        TransferChunkEntity result = transferService.commitChunk(transferId, 0, validChecksum, null, 8388608L);
        assertEquals(existing.getChunkIndex(), result.getChunkIndex());
        assertEquals(existing.getChecksum(), result.getChecksum());
        verify(chunkRepository, never()).saveAndFlush(any());
    }

    @Test
    void testCommitConflictOnDifferentChecksum() {
        String transferId = dummyTransfer.getTransferId();
        String checksum1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String checksum2 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        TransferChunkEntity existing = createChunkEntity(transferId, 0, checksum1, 8388608L, "key-0");
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(dummyTransfer));
        when(chunkRepository.findByTransferIdAndChunkIndex(transferId, 0)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () ->
                transferService.commitChunk(transferId, 0, checksum2, null, 8388608L));
    }

    @Test
    void testDirectDownloadUrlGeneration() {
        String transferId = dummyTransfer.getTransferId();
        String validChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        TransferChunkEntity existing = createChunkEntity(transferId, 0, validChecksum, 8388608L, "key-0");
        when(transferRepository.findById(transferId)).thenReturn(Optional.of(dummyTransfer));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc(transferId)).thenReturn(List.of(existing));
        when(chunkRepository.findByTransferIdAndChunkIndex(transferId, 0)).thenReturn(Optional.of(existing));
        when(b2ChunkStorage.generateDownloadPresignedUrl(eq(transferId), eq(0), eq(validChecksum), eq(8388608L), any(Duration.class)))
                .thenReturn(new ChunkDownloadUrlResponse(0, 8388608L, validChecksum, "https://s3.example.com/get-0", Instant.now().plusSeconds(900)));

        ChunkDownloadUrlResponse downloadResp = transferService.getChunkDownloadUrl(transferId, 0, dummyTransfer.getShareToken());
        assertNotNull(downloadResp);
        assertEquals("https://s3.example.com/get-0", downloadResp.getDownloadUrl());
        assertEquals(validChecksum, downloadResp.getChecksum());
    }
}
