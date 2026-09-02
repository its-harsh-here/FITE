package me.desair.spring.transfer.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.temporal.ChronoUnit;

public class Transfer {
    private final String id;
    private final String shareToken;
    private final String transferCode;
    private final String fileName;
    private final String contentType;
    private final long fileSize;
    private final long chunkSize;
    private final int totalChunks;
    private final Instant createdAt;
    private final Instant expiresAt;

    private TransferStatus status;
    private final Map<Integer, TransferChunk> chunks = new HashMap<>();

    public Transfer(String id, String shareToken, String transferCode, String fileName, String contentType, 
                    long fileSize, long chunkSize, int totalChunks, 
                    TransferStatus status, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.shareToken = shareToken;
        this.transferCode = transferCode;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Transfer(String id, String shareToken, String fileName, String contentType, 
                    long fileSize, long chunkSize, int totalChunks, 
                    TransferStatus status, Instant createdAt, Instant expiresAt) {
        this(id, shareToken, null, fileName, contentType, fileSize, chunkSize, totalChunks, status, createdAt, expiresAt);
    }

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // 32 unambiguous chars

    public static String generateTransferCode(int length) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length()));
        }
        return new String(chars);
    }

    public static Transfer createNew(String fileName, long fileSize, String contentType, long chunkSize) {
        if (fileSize <= 0) {
            throw new TransferDomainException("fileSize must be positive");
        }
        if (chunkSize <= 0) {
            throw new TransferDomainException("chunkSize must be positive");
        }

        String id = "tf_" + generateSecureToken(16);
        String token = "st_" + generateSecureToken(32);
        String code = generateTransferCode(6);
        
        int total = (int) Math.ceil((double) fileSize / chunkSize);
        Instant now = Instant.now();
        Instant expires = now.plus(7, ChronoUnit.DAYS);

        return new Transfer(id, token, code, fileName, contentType, fileSize, chunkSize, total, TransferStatus.CREATED, now, expires);
    }

    private static String generateSecureToken(int numBytes) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[numBytes];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now) || expiresAt.equals(now);
    }

    public long getExpectedChunkSize(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new TransferDomainException("Chunk index out of bounds");
        }
        if (chunkIndex == totalChunks - 1) {
            long remaining = fileSize % chunkSize;
            return remaining == 0 ? chunkSize : remaining;
        }
        return chunkSize;
    }

    public void checkAccess(String token, Instant now) {
        if (isExpired(now)) {
            throw new TransferExpiredException("Transfer is expired");
        }
        if (token == null || token.isBlank() || !this.shareToken.equals(token)) {
            throw new TransferDomainException("Invalid share token");
        }
    }

    public void checkUploadAllowed(Instant now) {
        if (isExpired(now)) {
            throw new TransferExpiredException("Transfer is expired");
        }
        if (status == TransferStatus.EXPIRED || status == TransferStatus.FAILED) {
            throw new TransferDomainException("Transfer is no longer active");
        }
        if (status == TransferStatus.COMPLETE) {
            throw new TransferDomainException("Transfer is already complete");
        }
    }

    public void markChunkAvailable(TransferChunk chunk, Instant now) {
        checkUploadAllowed(now);

        if (chunk.getIndex() >= totalChunks) {
            throw new TransferDomainException("Chunk index out of bounds");
        }

        TransferChunk existing = chunks.get(chunk.getIndex());
        if (existing != null) {
            if (!existing.getChecksum().equalsIgnoreCase(chunk.getChecksum())) {
                throw new TransferDomainException("Chunk already exists with different content");
            }
            return;
        }

        chunks.put(chunk.getIndex(), chunk);

        if (status == TransferStatus.CREATED) {
            status = TransferStatus.UPLOADING;
        }
    }

    public void complete(Instant now) {
        if (isExpired(now)) {
            throw new TransferDomainException("Cannot complete an expired transfer");
        }
        if (chunks.size() < totalChunks) {
            throw new TransferDomainException("Transfer is not complete; missing chunks");
        }
        status = TransferStatus.COMPLETE;
    }

    public void expire(Instant now) {
        if (!isExpired(now)) {
            throw new TransferDomainException("Transfer expiration time has not passed");
        }
        status = TransferStatus.EXPIRED;
    }

    public void loadExistingChunk(TransferChunk chunk) {
        chunks.put(chunk.getIndex(), chunk);
    }

    public String getId() { return id; }
    public String getShareToken() { return shareToken; }
    public String getTransferCode() { return transferCode; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public long getChunkSize() { return chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public TransferStatus getStatus() { return status; }
    
    public Set<Integer> getAvailableChunkIndexes() {
        return Collections.unmodifiableSet(chunks.keySet());
    }

    public TransferChunk getChunk(int index) {
        return chunks.get(index);
    }
}
