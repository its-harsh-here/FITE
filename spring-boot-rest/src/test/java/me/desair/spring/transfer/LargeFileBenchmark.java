package me.desair.spring.transfer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=8080",
    "spring.datasource.url=jdbc:h2:mem:benchdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "storage.local.directory=target/benchmark-chunks",
    "transfer.chunk-size-bytes=8388608", // Can be overridden in run configurations
    "spring.servlet.multipart.max-request-size=40MB", // Allows up to 32MB chunks
    "spring.servlet.multipart.max-file-size=50000MB"
})
public class LargeFileBenchmark {

    @Autowired
    private TestRestTemplate restTemplate;

    // Set to @Test locally to run the benchmark. @Disabled by default so it doesn't run during normal CI builds.
    @Disabled
    @Test
    public void runBenchmark() throws Exception {
        long[] chunkSizesToTest = {
            8 * 1024 * 1024L,
            16 * 1024 * 1024L,
            32 * 1024 * 1024L
        };
        
        long totalFileSize = 100 * 1024 * 1024L; // 100MB for baseline, change to 1GB or 20GB as needed.
        
        System.out.println("Starting Benchmark for File Size: " + totalFileSize / (1024 * 1024) + "MB");
        System.out.println("---------------------------------------------------");
        
        for (long chunkSize : chunkSizesToTest) {
            runBenchmarkForChunkSize(chunkSize, totalFileSize);
        }
    }

    private void runBenchmarkForChunkSize(long chunkSize, long totalFileSize) throws Exception {
        System.out.println("Testing Chunk Size: " + chunkSize / (1024 * 1024) + "MB");
        
        // 1. Create Transfer
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String createJson = String.format("{\"fileName\":\"bench.bin\",\"fileSize\":%d}", totalFileSize);
        
        long startTime = System.currentTimeMillis();
        ResponseEntity<Map> createResponse = restTemplate.postForEntity("/api/transfers", new HttpEntity<>(createJson, headers), Map.class);
        long createTime = System.currentTimeMillis() - startTime;
        
        assertEquals(200, createResponse.getStatusCodeValue());
        
        String transferId = (String) createResponse.getBody().get("transferId");
        
        // 2. Upload Chunks
        int totalChunks = (int) Math.ceil((double) totalFileSize / chunkSize);
        long totalUploadTime = 0;
        
        Random rand = new Random(42);
        
        for (int i = 0; i < totalChunks; i++) {
            long currentChunkSize = (i == totalChunks - 1 && totalFileSize % chunkSize != 0) ? (totalFileSize % chunkSize) : chunkSize;
            byte[] chunkData = new byte[(int) currentChunkSize];
            rand.nextBytes(chunkData);
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String checksum = HexFormat.of().formatHex(md.digest(chunkData));
            
            HttpHeaders chunkHeaders = new HttpHeaders();
            chunkHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            chunkHeaders.set("Upload-Checksum", checksum);
            // Simulate normal client IP to avoid Bucket4j blocking this local test
            chunkHeaders.set("X-Forwarded-For", "127.0.0." + (i % 200)); 
            
            long chunkStart = System.currentTimeMillis();
            ResponseEntity<Void> uploadResponse = restTemplate.exchange(
                    "/api/transfers/" + transferId + "/chunks/" + i,
                    HttpMethod.PUT,
                    new HttpEntity<>(chunkData, chunkHeaders),
                    Void.class);
            long chunkTime = System.currentTimeMillis() - chunkStart;
            
            assertEquals(200, uploadResponse.getStatusCodeValue());
            totalUploadTime += chunkTime;
        }
        
        // 3. Complete Transfer
        long completeStart = System.currentTimeMillis();
        ResponseEntity<Void> completeResponse = restTemplate.postForEntity("/api/transfers/" + transferId + "/complete", null, Void.class);
        long completeTime = System.currentTimeMillis() - completeStart;
        
        assertEquals(200, completeResponse.getStatusCodeValue());
        
        double totalSeconds = totalUploadTime / 1000.0;
        double throughputMbPerS = (totalFileSize / (1024.0 * 1024.0)) / totalSeconds;
        double avgChunkLatency = (double) totalUploadTime / totalChunks;
        
        System.out.printf("  - Total Chunks: %d\n", totalChunks);
        System.out.printf("  - Create Latency: %d ms\n", createTime);
        System.out.printf("  - Avg Chunk Latency: %.2f ms\n", avgChunkLatency);
        System.out.printf("  - Complete Latency: %d ms\n", completeTime);
        System.out.printf("  - Total Upload Time: %.2f s\n", totalSeconds);
        System.out.printf("  - Throughput: %.2f MB/s\n\n", throughputMbPerS);
    }
}
