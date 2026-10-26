package me.desair.spring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.desair.spring.transfer.api.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(properties = {
    "storage.type=local",
    "storage.local.directory=target/test-security-storage",
    "cors.allowed-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
public class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testCorsHeaders() throws Exception {
        mockMvc.perform(post("/api/transfers")
                .header("Origin", "http://localhost:5173")
                .header("X-Forwarded-For", "192.168.1.50")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"test.txt\",\"fileSize\":1024,\"contentType\":\"text/plain\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void testRateLimitingOnCreationEndpoint() throws Exception {
        // Create endpoint allows 10 per minute per IP.
        // We will loop 10 times using a dedicated spoofed IP to hit the limit deterministically.
        String spoofedIp = "192.168.1.100";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/transfers")
                    .header("X-Forwarded-For", spoofedIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fileName\":\"rate_test_" + i + ".txt\",\"fileSize\":1024}"))
                    .andExpect(status().isOk());
        }

        // 11th request should fail with 429 Too Many Requests
        mockMvc.perform(post("/api/transfers")
                .header("X-Forwarded-For", spoofedIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"rate_test_too_many.txt\",\"fileSize\":1024}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void testMaxFileSizeValidation() throws Exception {
        CreateTransferRequest req = new CreateTransferRequest();
        req.setFileName("too_big.txt");
        req.setFileSize(60000000000L); // 60GB, configured limit is 50GB

        mockMvc.perform(post("/api/transfers")
                .header("X-Forwarded-For", "192.168.1.150")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                        status == 400 || status == 500,
                        "Expected input validation failure status (400 or 500) but received: " + status
                    );
                });
    }
}
