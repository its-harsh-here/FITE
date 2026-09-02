package me.desair.spring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.desair.spring.transfer.api.CreateTransferRequest;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ProgressiveTransferIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testProgressiveE2E() throws Exception {
        // 1. Sender creates transfer (3 chunks total for 20MB file, chunk size is 8MB)
        CreateTransferRequest createReq = new CreateTransferRequest();
        createReq.setFileName("large_video.mp4");
        createReq.setFileSize(20 * 1024 * 1024); // 20 MB
        createReq.setContentType("video/mp4");

        String createResStr = mockMvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
                
        TransferEntity transfer = objectMapper.readValue(createResStr, TransferEntity.class);
        String tId = transfer.getTransferId();
        String token = transfer.getShareToken();

        // 2. Early Receiver Join: Receiver checks availability immediately
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        // 3. Sender Uploads Chunk 0 (8MB)
        byte[] chunk0 = new byte[8 * 1024 * 1024];
        String hash0 = calculateHash(chunk0);
        
        mockMvc.perform(put("/api/transfers/" + tId + "/chunks/0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Upload-Checksum", hash0)
                .content(chunk0))
                .andExpect(status().isOk());

        // 4. Receiver Detects New Data and Downloads it
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[0]"));
                
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks/0?token=" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Upload-Checksum", hash0));

        // 5. Receiver requests chunk 1 before it exists (Caught-up waiting)
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks/1?token=" + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHUNK_NOT_AVAILABLE"));

        // 6. Sender Uploads Chunk 1 (8MB)
        byte[] chunk1 = new byte[8 * 1024 * 1024];
        chunk1[0] = 1; // differentiate
        String hash1 = calculateHash(chunk1);
        
        mockMvc.perform(put("/api/transfers/" + tId + "/chunks/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Upload-Checksum", hash1)
                .content(chunk1))
                .andExpect(status().isOk());

        // 7. Receiver Detects New Data
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[0,1]"));
                
        // 8. Sender Uploads Chunk 2 (Final 4MB chunk)
        byte[] chunk2 = new byte[4 * 1024 * 1024];
        String hash2 = calculateHash(chunk2);
        
        mockMvc.perform(put("/api/transfers/" + tId + "/chunks/2")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Upload-Checksum", hash2)
                .content(chunk2))
                .andExpect(status().isOk());

        // 9. Sender completes transfer
        mockMvc.perform(post("/api/transfers/" + tId + "/complete"))
                .andExpect(status().isOk());

        // 10. Receiver gets final state (Final Correctness)
        mockMvc.perform(get("/api/transfers/" + tId + "?token=" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));
                
        mockMvc.perform(get("/api/transfers/" + tId + "/chunks?token=" + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[0,1,2]"));
    }
    
    private String calculateHash(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
