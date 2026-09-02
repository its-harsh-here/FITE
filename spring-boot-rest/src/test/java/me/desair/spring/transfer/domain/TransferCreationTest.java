package me.desair.spring.transfer.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TransferCreationTest {

    @Test
    void testFileSmallerThanChunkSize() {
        Transfer transfer = Transfer.createNew("small.txt", 500, "text/plain", 1000);
        assertEquals(1, transfer.getTotalChunks());
        assertEquals(500, transfer.getFileSize());
    }

    @Test
    void testExactChunkSizeMultiple() {
        Transfer transfer = Transfer.createNew("exact.txt", 3000, "text/plain", 1000);
        assertEquals(3, transfer.getTotalChunks());
    }

    @Test
    void testNonDivisibleFileWithSmallerFinalChunk() {
        Transfer transfer = Transfer.createNew("large.txt", 3500, "text/plain", 1000);
        assertEquals(4, transfer.getTotalChunks());
    }

    @Test
    void testInvalidSizes() {
        assertThrows(TransferDomainException.class, () -> Transfer.createNew("zero.txt", 0, "text", 1000));
        assertThrows(TransferDomainException.class, () -> Transfer.createNew("neg.txt", -500, "text", 1000));
        assertThrows(TransferDomainException.class, () -> Transfer.createNew("zero_chunk.txt", 1000, "text", 0));
    }

    @Test
    void testCapabilityGenerationAndExpiration() {
        Transfer t1 = Transfer.createNew("test.txt", 1000, "text/plain", 100);
        Transfer t2 = Transfer.createNew("test2.txt", 1000, "text/plain", 100);

        assertNotNull(t1.getId());
        assertTrue(t1.getId().startsWith("tf_"));
        assertEquals(35, t1.getId().length()); // "tf_" (3) + 16 bytes hex (32) = 35
        
        assertNotNull(t1.getShareToken());
        assertTrue(t1.getShareToken().startsWith("st_"));
        assertEquals(67, t1.getShareToken().length()); // "st_" (3) + 32 bytes hex (64) = 67

        assertNotNull(t1.getTransferCode());
        assertEquals(6, t1.getTransferCode().length());
        assertTrue(t1.getTransferCode().matches("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$"));

        assertNotEquals(t1.getId(), t2.getId());
        assertNotEquals(t1.getShareToken(), t2.getShareToken());
        assertNotEquals(t1.getTransferCode(), t2.getTransferCode());

        assertNotNull(t1.getExpiresAt());
        assertTrue(t1.getExpiresAt().isAfter(Instant.now()));
    }
}
