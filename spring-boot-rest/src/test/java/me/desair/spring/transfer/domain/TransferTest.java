package me.desair.spring.transfer.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;

class TransferTest {

    @Test
    void testCreateNew() {
        Transfer transfer = Transfer.createNew("test.txt", 1000, "text/plain", 100);
        assertEquals(TransferStatus.CREATED, transfer.getStatus());
        assertEquals(10, transfer.getTotalChunks());
        assertNotNull(transfer.getId());
        assertNotNull(transfer.getShareToken());
    }

    @Test
    void testExpiration() {
        Instant now = Instant.now();
        Transfer transfer = new Transfer("id", "token", "test", "txt", 100, 10, 10, 
                TransferStatus.CREATED, now.minus(8, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        
        assertTrue(transfer.isExpired(now));
        
        assertThrows(TransferExpiredException.class, () -> transfer.checkAccess("token", now));
        assertThrows(TransferExpiredException.class, () -> transfer.checkUploadAllowed(now));
    }

    @Test
    void testShareTokenValidation() {
        Instant now = Instant.now();
        Transfer transfer = Transfer.createNew("test", 100, "txt", 10);
        
        assertDoesNotThrow(() -> transfer.checkAccess(transfer.getShareToken(), now));
        assertThrows(TransferDomainException.class, () -> transfer.checkAccess("wrong", now));
    }

    @Test
    void testChunkAvailabilityRules() {
        Instant now = Instant.now();
        Transfer transfer = Transfer.createNew("test", 100, "txt", 10); // 10 chunks total
        
        TransferChunk chunk = new TransferChunk(0, 10, "abc", now);
        transfer.markChunkAvailable(chunk, now);
        
        assertEquals(TransferStatus.UPLOADING, transfer.getStatus());
        assertTrue(transfer.getAvailableChunkIndexes().contains(0));
        
        // Cannot upload out of bounds chunk
        TransferChunk oob = new TransferChunk(10, 10, "abc", now); // max index is 9
        assertThrows(TransferDomainException.class, () -> transfer.markChunkAvailable(oob, now));
    }

    @Test
    void testIdempotentChunkUpload() {
        Instant now = Instant.now();
        Transfer transfer = Transfer.createNew("test", 100, "txt", 10);
        
        TransferChunk chunk = new TransferChunk(0, 10, "abc", now);
        transfer.markChunkAvailable(chunk, now);
        
        // Same chunk again is OK
        assertDoesNotThrow(() -> transfer.markChunkAvailable(new TransferChunk(0, 10, "abc", now), now));
        
        // Different checksum is rejected
        assertThrows(TransferDomainException.class, () -> transfer.markChunkAvailable(new TransferChunk(0, 10, "def", now), now));
    }

    @Test
    void testCompletionDetermination() {
        Instant now = Instant.now();
        Transfer transfer = Transfer.createNew("test", 20, "txt", 10); // 2 chunks total
        
        assertThrows(TransferDomainException.class, () -> transfer.complete(now));
        
        transfer.markChunkAvailable(new TransferChunk(0, 10, "a", now), now);
        assertThrows(TransferDomainException.class, () -> transfer.complete(now));
        
        transfer.markChunkAvailable(new TransferChunk(1, 10, "b", now), now);
        assertDoesNotThrow(() -> transfer.complete(now));
        assertEquals(TransferStatus.COMPLETE, transfer.getStatus());
    }

    @Test
    void testChunkValidation() {
        assertThrows(TransferDomainException.class, () -> new TransferChunk(-1, 10, "abc", Instant.now()));
        assertThrows(TransferDomainException.class, () -> new TransferChunk(0, 0, "abc", Instant.now()));
        assertThrows(TransferDomainException.class, () -> new TransferChunk(0, 10, null, Instant.now()));
    }
}
