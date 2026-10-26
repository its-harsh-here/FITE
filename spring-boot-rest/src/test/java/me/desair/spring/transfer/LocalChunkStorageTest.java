package me.desair.spring.transfer;

import me.desair.spring.transfer.infrastructure.storage.LocalChunkStorage;
import me.desair.spring.transfer.infrastructure.storage.StorageException;
import me.desair.spring.transfer.infrastructure.storage.StorageFileNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalChunkStorageTest {

    private Path tempDir;
    private LocalChunkStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("chunk-storage-test");
        storage = new LocalChunkStorage(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        FileSystemUtils.deleteRecursively(tempDir);
    }

    @Test
    void testPutAndGetChunk() throws Exception {
        String transferId = "tf_123";
        int chunkIndex = 0;
        String checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        byte[] data = "Hello Chunk".getBytes();

        assertFalse(storage.exists(transferId, chunkIndex, checksum));

        storage.putChunk(transferId, chunkIndex, checksum, new ByteArrayInputStream(data), data.length);

        assertTrue(storage.exists(transferId, chunkIndex, checksum));

        try (InputStream is = storage.getChunk(transferId, chunkIndex, checksum)) {
            assertArrayEquals(data, is.readAllBytes());
        }
    }

    @Test
    void testGetMissingChunkThrowsStorageFileNotFound() {
        assertThrows(StorageFileNotFoundException.class, () -> storage.getChunk("tf_missing", 0, "missing_hash"));
    }

    @Test
    void testDeleteChunkIsIdempotent() throws Exception {
        String transferId = "tf_del";
        int chunkIndex = 1;
        String checksum = "abc123hash";
        
        // Deleting non-existent chunk should not throw
        assertDoesNotThrow(() -> storage.deleteChunk(transferId, chunkIndex, checksum));

        byte[] data = "Data".getBytes();
        storage.putChunk(transferId, chunkIndex, checksum, new ByteArrayInputStream(data), data.length);
        
        assertTrue(storage.exists(transferId, chunkIndex, checksum));
        
        storage.deleteChunk(transferId, chunkIndex, checksum);
        
        assertFalse(storage.exists(transferId, chunkIndex, checksum));
        
        // Second delete should also not throw
        assertDoesNotThrow(() -> storage.deleteChunk(transferId, chunkIndex, checksum));
    }

    @Test
    void testDeleteTransfer() throws Exception {
        String transferId = "tf_multi";
        byte[] data = "Data".getBytes();
        String hash0 = "hash_0";
        String hash1 = "hash_1";
        
        storage.putChunk(transferId, 0, hash0, new ByteArrayInputStream(data), data.length);
        storage.putChunk(transferId, 1, hash1, new ByteArrayInputStream(data), data.length);
        
        assertTrue(storage.exists(transferId, 0, hash0));
        assertTrue(storage.exists(transferId, 1, hash1));
        
        storage.deleteTransfer(transferId);
        
        assertFalse(storage.exists(transferId, 0, hash0));
        assertFalse(storage.exists(transferId, 1, hash1));
        
        // Idempotent
        assertDoesNotThrow(() -> storage.deleteTransfer(transferId));
    }

    @Test
    void testTransferStorageIsolation() throws Exception {
        String t1 = "tf_iso_1";
        String t2 = "tf_iso_2";
        String hash1 = "hash_iso_1";
        String hash2 = "hash_iso_2";
        byte[] data1 = "Data 1".getBytes();
        byte[] data2 = "Data 2".getBytes();

        storage.putChunk(t1, 0, hash1, new ByteArrayInputStream(data1), data1.length);
        storage.putChunk(t2, 0, hash2, new ByteArrayInputStream(data2), data2.length);

        assertTrue(storage.exists(t1, 0, hash1));
        assertTrue(storage.exists(t2, 0, hash2));

        // Delete t1 must not affect t2
        storage.deleteTransfer(t1);

        assertFalse(storage.exists(t1, 0, hash1));
        assertTrue(storage.exists(t2, 0, hash2));

        try (InputStream is = storage.getChunk(t2, 0, hash2)) {
            assertArrayEquals(data2, is.readAllBytes());
        }
    }

    @Test
    void testPathTraversalPrevention() {
        String badTransferId = "../../../windows/system32";
        byte[] data = "Data".getBytes();
        
        StorageException e1 = assertThrows(StorageException.class, () ->
            storage.putChunk(badTransferId, 0, "hash", new ByteArrayInputStream(data), data.length));
        assertTrue(e1.getMessage().contains("Invalid transferId"));
        
        StorageException e2 = assertThrows(StorageException.class, () -> 
            storage.deleteTransfer(badTransferId));
        assertTrue(e2.getMessage().contains("Invalid transferId"));
    }

    @Test
    void testNegativeChunkIndex() {
        String transferId = "tf_valid";
        byte[] data = "Data".getBytes();
        
        StorageException e = assertThrows(StorageException.class, () -> 
            storage.putChunk(transferId, -1, "hash", new ByteArrayInputStream(data), data.length));
        assertTrue(e.getMessage().contains("Chunk index cannot be negative"));
    }
}
