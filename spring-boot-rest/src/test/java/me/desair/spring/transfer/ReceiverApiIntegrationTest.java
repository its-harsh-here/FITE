package me.desair.spring.transfer;

import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import me.desair.spring.transfer.infrastructure.storage.ChunkStorage;
import me.desair.spring.transfer.infrastructure.storage.StorageFileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.List;

import me.desair.spring.transfer.domain.TransferStatus;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ReceiverApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransferRepository transferRepository;

    @MockBean
    private TransferChunkRepository chunkRepository;

    @MockBean
    private ChunkStorage chunkStorage;

    private TransferEntity mockTransfer() {
        TransferEntity t = new TransferEntity();
        t.setTransferId("tf_test");
        t.setShareToken("st_test");
        t.setTransferCode("ABC7K9");
        t.setFileName("hello.txt");
        t.setFileSize(100);
        t.setChunkSize(100);
        t.setTotalChunks(1);
        t.setStatus(TransferStatus.UPLOADING);
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return t;
    }

    private TransferChunkEntity mockChunk() {
        TransferChunkEntity c = new TransferChunkEntity();
        c.setTransferId("tf_test");
        c.setChunkIndex(0);
        c.setSize(100);
        c.setChecksum("abcdef");
        c.setUploadedAt(Instant.now());
        return c;
    }

    @Test
    void testTransferNotFound() throws Exception {
        when(transferRepository.findById("tf_missing")).thenReturn(Optional.empty());
        
        mockMvc.perform(get("/api/transfers/tf_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void testTransferInvalidToken() throws Exception {
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        
        mockMvc.perform(get("/api/transfers/tf_test?token=wrong_token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void testTransferSuccess() throws Exception {
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        
        mockMvc.perform(get("/api/transfers/tf_test?token=st_test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("tf_test"));
    }

    @Test
    void testTransferCodeLookupSuccess() throws Exception {
        when(transferRepository.findByTransferCode("ABC7K9")).thenReturn(Optional.of(mockTransfer()));

        mockMvc.perform(get("/api/transfers/code/ABC7K9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value("tf_test"))
                .andExpect(jsonPath("$.shareToken").value("st_test"))
                .andExpect(jsonPath("$.transferCode").value("ABC7K9"));
    }

    @Test
    void testTransferCodeLookupNotFound() throws Exception {
        when(transferRepository.findByTransferCode("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/transfers/code/UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void testChunkNotAvailable() throws Exception {
        // Chunk is not in the list of available chunks in domain
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_test")).thenReturn(List.of());

        mockMvc.perform(get("/api/transfers/tf_test/chunks/0?token=st_test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHUNK_NOT_AVAILABLE"));
    }

    @Test
    void testChunkSuccess() throws Exception {
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_test")).thenReturn(List.of(mockChunk()));
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_test", 0)).thenReturn(Optional.of(mockChunk()));
        
        ByteArrayInputStream bais = new ByteArrayInputStream("hello".getBytes());
        when(chunkStorage.getChunk("tf_test", 0, "abcdef")).thenReturn(bais);

        mockMvc.perform(get("/api/transfers/tf_test/chunks/0?token=st_test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Upload-Checksum", "abcdef"))
                .andExpect(content().string("hello"));
    }

    @Test
    void testStorageInconsistency() throws Exception {
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_test")).thenReturn(List.of(mockChunk()));
        when(chunkRepository.findByTransferIdAndChunkIndex("tf_test", 0)).thenReturn(Optional.of(mockChunk()));
        
        when(chunkStorage.getChunk("tf_test", 0, "abcdef")).thenThrow(new StorageFileNotFoundException("missing"));

        mockMvc.perform(get("/api/transfers/tf_test/chunks/0?token=st_test"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_FAILURE"));
    }

    @Test
    void testObjectExistsButMetadataMissing() throws Exception {
        // Transfer exists, but metadata for chunk 0 is missing.
        when(transferRepository.findById("tf_test")).thenReturn(Optional.of(mockTransfer()));
        when(chunkRepository.findByTransferIdOrderByChunkIndexAsc("tf_test")).thenReturn(List.of());
        
        // Even if the object exists in storage (orphaned data), the API must return CHUNK_NOT_AVAILABLE
        when(chunkStorage.exists(eq("tf_test"), eq(0), any())).thenReturn(true);

        mockMvc.perform(get("/api/transfers/tf_test/chunks/0?token=st_test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHUNK_NOT_AVAILABLE"));
    }
}
