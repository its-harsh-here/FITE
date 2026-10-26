package me.desair.spring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.desair.spring.transfer.api.CreateTransferRequest;
import me.desair.spring.transfer.application.TransferCleanupService;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import me.desair.spring.transfer.infrastructure.storage.ChunkStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "transfer.chunk-size-bytes=1024",
    "storage.type=local",
    "storage.local.directory=target/test-cleanup-storage"
})
@AutoConfigureMockMvc
public class TransferCleanupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransferChunkRepository chunkRepository;

    @SpyBean
    private ChunkStorage chunkStorage;

    @Autowired
    private TransferCleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        transferRepository.deleteAll();
        chunkRepository.deleteAll();
    }

    private TransferEntity createAndUploadTransfer() throws Exception {
        CreateTransferRequest req = new CreateTransferRequest();
        req.setFileName("test.txt");
        req.setFileSize(2048L);
        req.setContentType("text/plain");

        String json = mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        TransferEntity transfer = objectMapper.readValue(json, TransferEntity.class);

        // Upload chunk 0
        mockMvc.perform(put("/api/transfers/" + transfer.getTransferId() + "/chunks/0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[1024]))
                .andExpect(status().isOk());

        return transferRepository.findById(transfer.getTransferId()).get();
    }

    private void expireTransfer(String transferId) {
        TransferEntity entity = transferRepository.findById(transferId).get();
        entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        transferRepository.saveAndFlush(entity);
    }

    @Test
    void testNormalCleanupAndMetadataDeletion() throws Exception {
        TransferEntity t = createAndUploadTransfer();
        expireTransfer(t.getTransferId());

        cleanupService.cleanupExpiredTransfers();

        // Metadata deleted
        assertThat(transferRepository.findById(t.getTransferId())).isEmpty();
        assertThat(chunkRepository.findByTransferIdOrderByChunkIndexAsc(t.getTransferId())).isEmpty();

        // Files deleted
        Path storageDir = Paths.get("target/test-cleanup-storage", t.getTransferId());
        assertThat(Files.exists(storageDir)).isFalse();
    }

    @Test
    void testRepeatedCleanupIsIdempotent() throws Exception {
        TransferEntity t = createAndUploadTransfer();
        expireTransfer(t.getTransferId());

        cleanupService.cleanupExpiredTransfers();
        // Run it again! Should not throw errors
        cleanupService.cleanupExpiredTransfers();

        assertThat(transferRepository.findById(t.getTransferId())).isEmpty();
    }

    @Test
    void testPartialCleanupAndRetry() throws Exception {
        TransferEntity t = createAndUploadTransfer();
        expireTransfer(t.getTransferId());

        // Sabotage storage deletion
        doThrow(new RuntimeException("Simulated storage failure")).when(chunkStorage).deleteTransfer(t.getTransferId());

        cleanupService.cleanupExpiredTransfers();

        // Transfer status is EXPIRED (access rejected) but metadata not deleted because storage failed
        TransferEntity remaining = transferRepository.findById(t.getTransferId()).get();
        assertThat(remaining.getStatus().name()).isEqualTo("EXPIRED");
        assertThat(chunkRepository.findByTransferIdOrderByChunkIndexAsc(t.getTransferId())).isNotEmpty();

        // 410 Gone on access
        mockMvc.perform(get("/api/transfers/" + t.getTransferId() + "?token=" + t.getShareToken()))
                .andExpect(status().isGone());

        // Fix storage deletion
        doCallRealMethod().when(chunkStorage).deleteTransfer(t.getTransferId());

        // Retry cleanup
        cleanupService.cleanupExpiredTransfers();

        // Fully cleaned
        assertThat(transferRepository.findById(t.getTransferId())).isEmpty();
    }

    @Test
    void testMissingObjectDuringCleanup() throws Exception {
        TransferEntity t = createAndUploadTransfer();
        expireTransfer(t.getTransferId());

        // Manually delete the files before cleanup
        Path storageDir = Paths.get("target/test-cleanup-storage", t.getTransferId());
        org.springframework.util.FileSystemUtils.deleteRecursively(storageDir);

        assertThat(Files.exists(storageDir)).isFalse();

        // Cleanup should succeed and delete metadata despite files already missing
        cleanupService.cleanupExpiredTransfers();

        assertThat(transferRepository.findById(t.getTransferId())).isEmpty();
        assertThat(chunkRepository.findByTransferIdOrderByChunkIndexAsc(t.getTransferId())).isEmpty();
    }
}
