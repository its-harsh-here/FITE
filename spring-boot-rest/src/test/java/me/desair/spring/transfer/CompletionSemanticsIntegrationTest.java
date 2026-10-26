package me.desair.spring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.desair.spring.transfer.api.CreateTransferRequest;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "transfer.chunk-size-bytes=1024",
    "storage.type=local",
    "storage.local.directory=target/test-completion-storage"
})
@AutoConfigureMockMvc
public class CompletionSemanticsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void testNormalCompletion() throws Exception {
        TransferEntity transfer = createTransfer(2); // 2 chunks
        String tId = transfer.getTransferId();
        String token = transfer.getShareToken();

        uploadChunk(tId, 0);
        uploadChunk(tId, 1);

        mockMvc.perform(post("/api/transfers/" + tId + "/complete"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transfers/" + tId + "?token=" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    @Test
    void testIncompleteTransferCannotBeCompleted() throws Exception {
        TransferEntity transfer = createTransfer(2); // 2 chunks expected
        String tId = transfer.getTransferId();

        // Upload only 1 chunk out of 2
        uploadChunk(tId, 0);

        // Attempting to complete must fail
        mockMvc.perform(post("/api/transfers/" + tId + "/complete"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSenderAbandonmentAndReceiverBehavior() throws Exception {
        TransferEntity transfer = createTransfer(2); // 2 chunks
        String tId = transfer.getTransferId();
        String token = transfer.getShareToken();

        // 1. Sender uploads all chunks
        uploadChunk(tId, 0);
        uploadChunk(tId, 1);

        // 2. Sender ABANDONS the transfer (does NOT call /complete)
        // Status remains UPLOADING
        mockMvc.perform(get("/api/transfers/" + tId + "?token=" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADING"));

        // 3. Receiver can still fetch chunks successfully!
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[0,1]"));

        mockMvc.perform(get("/api/transfers/" + tId + "/chunks/1?token=" + token))
                .andExpect(status().isOk());
    }

    @Test
    void testExpirationBackstop() throws Exception {
        TransferEntity transfer = createTransfer(2);
        String tId = transfer.getTransferId();
        String token = transfer.getShareToken();

        // Expire the transfer directly in DB to simulate passing time
        TransferEntity entity = transferRepository.findById(tId).get();
        entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        transferRepository.save(entity);

        // 1. Attempting to get details -> 410 Gone
        mockMvc.perform(get("/api/transfers/" + tId + "?token=" + token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("TRANSFER_EXPIRED"));

        // 2. Attempting to get chunks -> 410 Gone
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("TRANSFER_EXPIRED"));
                
        // 3. Attempting to upload -> 410 Gone
        byte[] data = new byte[1024];
        mockMvc.perform(put("/api/transfers/" + tId + "/chunks/0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(data))
                .andExpect(status().isGone());
    }

    private TransferEntity createTransfer(int chunks) throws Exception {
        CreateTransferRequest req = new CreateTransferRequest();
        req.setFileName("test.txt");
        req.setFileSize((long) chunks * 1024);
        req.setContentType("text/plain");

        String json = mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(json, TransferEntity.class);
    }

    private void uploadChunk(String tId, int index) throws Exception {
        byte[] chunk = new byte[1024];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = HexFormat.of().formatHex(digest.digest(chunk));

        mockMvc.perform(put("/api/transfers/" + tId + "/chunks/" + index)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Upload-Checksum", hash)
                .content(chunk))
                .andExpect(status().isOk());
    }
}
