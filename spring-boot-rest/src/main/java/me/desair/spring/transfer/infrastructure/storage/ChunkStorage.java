package me.desair.spring.transfer.infrastructure.storage;

import java.io.InputStream;

public interface ChunkStorage {
    void putChunk(String transferId, int chunkIndex, String checksum, InputStream data, long size) throws Exception;
    InputStream getChunk(String transferId, int chunkIndex, String checksum) throws Exception;
    boolean exists(String transferId, int chunkIndex, String checksum);
    void deleteChunk(String transferId, int chunkIndex, String checksum) throws Exception;
    void deleteTransfer(String transferId) throws Exception;
}
