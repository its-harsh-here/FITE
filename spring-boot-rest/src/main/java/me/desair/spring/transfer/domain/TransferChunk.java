package me.desair.spring.transfer.domain;

import java.time.Instant;

public class TransferChunk {
    private final int index;
    private final long size;
    private final String checksum;
    private final Instant uploadedAt;

    public TransferChunk(int index, long size, String checksum, Instant uploadedAt) {
        if (size <= 0) {
            throw new TransferDomainException("Chunk size must be greater than zero");
        }
        if (checksum == null || checksum.isBlank()) {
            throw new TransferDomainException("Chunk checksum is required");
        }
        if (index < 0) {
            throw new TransferDomainException("Chunk index cannot be negative");
        }
        this.index = index;
        this.size = size;
        this.checksum = checksum;
        this.uploadedAt = uploadedAt;
    }

    public int getIndex() { return index; }
    public long getSize() { return size; }
    public String getChecksum() { return checksum; }
    public Instant getUploadedAt() { return uploadedAt; }
}
