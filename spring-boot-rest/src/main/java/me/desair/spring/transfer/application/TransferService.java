package me.desair.spring.transfer.application;

import me.desair.spring.transfer.api.ChunkDownloadUrlResponse;
import me.desair.spring.transfer.api.ChunkUploadUrlResponse;
import me.desair.spring.transfer.application.exception.ChunkNotAvailableException;
import me.desair.spring.transfer.application.exception.TransferNotFoundException;
import me.desair.spring.transfer.domain.TransferExpiredException;
import me.desair.spring.transfer.domain.TransferStatus;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import me.desair.spring.transfer.infrastructure.storage.B2ChunkStorage;
import me.desair.spring.transfer.infrastructure.storage.ChunkStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import me.desair.spring.transfer.domain.Transfer;
import me.desair.spring.transfer.domain.TransferChunk;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.io.FileInputStream;

@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final TransferChunkRepository chunkRepository;
    private final ChunkStorage chunkStorage;
    private final long defaultChunkSize;

    public TransferService(TransferRepository transferRepository, 
                           TransferChunkRepository chunkRepository, 
                           ChunkStorage chunkStorage,
                           @org.springframework.beans.factory.annotation.Value("${transfer.chunk-size-bytes:8388608}") long defaultChunkSize) {
        this.transferRepository = transferRepository;
        this.chunkRepository = chunkRepository;
        this.chunkStorage = chunkStorage;
        this.defaultChunkSize = defaultChunkSize;
    }

    private Transfer toDomain(TransferEntity entity) {
        Transfer domain = new Transfer(
            entity.getTransferId(), entity.getShareToken(), entity.getTransferCode(), entity.getFileName(),
            entity.getContentType(), entity.getFileSize(), entity.getChunkSize(),
            entity.getTotalChunks(), entity.getStatus(), entity.getCreatedAt(), entity.getExpiresAt()
        );
        chunkRepository.findByTransferIdOrderByChunkIndexAsc(entity.getTransferId()).forEach(chunkEntity -> {
            domain.loadExistingChunk(new TransferChunk(
                chunkEntity.getChunkIndex(), chunkEntity.getSize(),
                chunkEntity.getChecksum(), chunkEntity.getUploadedAt()
            ));
        });
        return domain;
    }

    private void saveDomain(Transfer domain) {
        TransferEntity entity = transferRepository.findById(domain.getId()).orElseGet(TransferEntity::new);
        entity.setTransferId(domain.getId());
        entity.setShareToken(domain.getShareToken());
        entity.setTransferCode(domain.getTransferCode());
        entity.setFileName(domain.getFileName());
        entity.setContentType(domain.getContentType());
        entity.setFileSize(domain.getFileSize());
        entity.setChunkSize(domain.getChunkSize());
        entity.setTotalChunks(domain.getTotalChunks());
        entity.setStatus(domain.getStatus());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setExpiresAt(domain.getExpiresAt());
        transferRepository.save(entity);
        
        domain.getAvailableChunkIndexes().forEach(index -> {
            Optional<TransferChunkEntity> existing = chunkRepository.findByTransferIdAndChunkIndex(domain.getId(), index);
            if (existing.isEmpty()) {
                TransferChunk c = domain.getChunk(index);
                TransferChunkEntity ce = new TransferChunkEntity();
                ce.setTransferId(domain.getId());
                ce.setChunkIndex(c.getIndex());
                ce.setSize(c.getSize());
                ce.setChecksum(c.getChecksum());
                ce.setUploadedAt(c.getUploadedAt());
                chunkRepository.save(ce);
            }
        });
    }

    @Transactional
    public TransferEntity createTransfer(String fileName, long fileSize, String contentType) {
        Transfer domain = Transfer.createNew(fileName, fileSize, contentType, defaultChunkSize);
        saveDomain(domain);
        return transferRepository.findById(domain.getId()).get();
    }

    public TransferEntity getTransfer(String transferId, String token) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
        Transfer domain = toDomain(entity);
        domain.checkAccess(token, Instant.now());
        return entity;
    }

    public TransferEntity getTransferByCode(String transferCode) {
        if (transferCode == null || transferCode.isBlank()) {
            throw new TransferNotFoundException("Transfer not found");
        }
        TransferEntity entity = transferRepository.findByTransferCode(transferCode.trim().toUpperCase())
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
        Transfer domain = toDomain(entity);
        if (domain.isExpired(Instant.now())) {
            throw new TransferExpiredException("Transfer is expired");
        }
        return entity;
    }

    public List<Integer> getAvailableChunks(String transferId, String token) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
        Transfer domain = toDomain(entity);
        domain.checkAccess(token, Instant.now());
        return domain.getAvailableChunkIndexes().stream().toList();
    }

    public static String normalizeChecksum(String checksum) {
        if (checksum == null) {
            return null;
        }
        String normalized = checksum.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String lower = normalized.toLowerCase();
        if (lower.startsWith("sha256:")) {
            normalized = normalized.substring(7).trim();
        } else if (lower.startsWith("sha-256:")) {
            normalized = normalized.substring(9).trim();
        }
        if (!normalized.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("Invalid checksum format");
        }
        return normalized.toLowerCase();
    }

    public ChunkUploadUrlResponse getChunkUploadUrl(String transferId, int chunkIndex, String checksum, String md5Checksum, long size) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
            
        Transfer domain = toDomain(entity);
        domain.checkUploadAllowed(Instant.now());
        
        long expectedSize = domain.getExpectedChunkSize(chunkIndex);
        if (size != expectedSize) {
            throw new IllegalArgumentException("Invalid chunk size. Expected " + expectedSize + " but got " + size);
        }
        
        String normalizedChecksum = normalizeChecksum(checksum);
        if (normalizedChecksum == null) {
            throw new IllegalArgumentException("Checksum is required");
        }
        
        if (!(chunkStorage instanceof B2ChunkStorage b2Storage)) {
            throw new UnsupportedOperationException("Direct presigned upload URLs are only supported with B2 storage");
        }
        
        return b2Storage.generateUploadPresignedUrl(transferId, chunkIndex, normalizedChecksum, md5Checksum, size, Duration.ofMinutes(15));
    }

    @Transactional
    public TransferChunkEntity commitChunk(String transferId, int chunkIndex, String checksum, String md5Checksum, long size) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
            
        Transfer domain = toDomain(entity);
        domain.checkUploadAllowed(Instant.now());
        
        long expectedSize = domain.getExpectedChunkSize(chunkIndex);
        if (size != expectedSize) {
            throw new IllegalArgumentException("Invalid chunk size. Expected " + expectedSize + " but got " + size);
        }
        
        String normalizedChecksum = normalizeChecksum(checksum);
        if (normalizedChecksum == null) {
            throw new IllegalArgumentException("Checksum is required");
        }

        // Pre-check Idempotency if already in DB
        Optional<TransferChunkEntity> existingChunk = chunkRepository.findByTransferIdAndChunkIndex(transferId, chunkIndex);
        if (existingChunk.isPresent()) {
            if (!existingChunk.get().getChecksum().equalsIgnoreCase(normalizedChecksum)) {
                throw new IllegalStateException("Chunk already exists with different content");
            }
            return existingChunk.get();
        }

        if (chunkStorage instanceof B2ChunkStorage b2Storage) {
            boolean valid = b2Storage.verifyChunkObject(transferId, chunkIndex, normalizedChecksum, md5Checksum, expectedSize);
            if (!valid) {
                throw new IllegalArgumentException("Chunk object validation failed in Backblaze B2");
            }
        }

        TransferChunkEntity chunkEntity = new TransferChunkEntity();
        chunkEntity.setTransferId(transferId);
        chunkEntity.setChunkIndex(chunkIndex);
        chunkEntity.setSize(size);
        chunkEntity.setChecksum(normalizedChecksum);
        chunkEntity.setStorageKey("transfers/" + transferId + "/chunks/" + String.format("%06d_%s", chunkIndex, normalizedChecksum));
        chunkEntity.setUploadedAt(Instant.now());

        try {
            chunkRepository.saveAndFlush(chunkEntity);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            Optional<TransferChunkEntity> winningChunk = chunkRepository.findByTransferIdAndChunkIndex(transferId, chunkIndex);
            if (winningChunk.isPresent() && winningChunk.get().getChecksum().equalsIgnoreCase(normalizedChecksum)) {
                return winningChunk.get();
            } else {
                throw new IllegalStateException("Chunk already exists with different content");
            }
        }

        if (entity.getStatus() == TransferStatus.CREATED) {
            entity.setStatus(TransferStatus.UPLOADING);
            transferRepository.save(entity);
        }
        
        return chunkEntity;
    }

    public ChunkDownloadUrlResponse getChunkDownloadUrl(String transferId, int chunkIndex, String token) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
        Transfer domain = toDomain(entity);
        domain.checkAccess(token, Instant.now());
        
        TransferChunkEntity chunkInfo = getChunkInfo(transferId, chunkIndex, token);
        
        if (!(chunkStorage instanceof B2ChunkStorage b2Storage)) {
            throw new UnsupportedOperationException("Direct presigned download URLs are only supported with B2 storage");
        }
        
        return b2Storage.generateDownloadPresignedUrl(transferId, chunkIndex, chunkInfo.getChecksum(), chunkInfo.getSize(), Duration.ofMinutes(15));
    }

    @Transactional
    public void uploadChunk(String transferId, int chunkIndex, String expectedChecksum, InputStream data, long size) throws Exception {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
            
        Transfer domain = toDomain(entity);
        
        // 2. Validate transfer
        domain.checkUploadAllowed(Instant.now());
        
        // 3. Validate chunk index & Expected size
        long expectedSize = domain.getExpectedChunkSize(chunkIndex);
        
        // 1. Receive bytes and calculate checksum
        Path tempFile = Files.createTempFile("chunk-", ".tmp");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long receivedSize = 0;
            try (DigestInputStream dis = new DigestInputStream(data, digest)) {
                receivedSize = Files.copy(dis, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            
            // 4. Validate expected chunk size
            if (receivedSize != expectedSize) {
                throw new IllegalArgumentException("Invalid chunk size. Expected " + expectedSize + " but got " + receivedSize);
            }
            
            String calculatedChecksum = HexFormat.of().formatHex(digest.digest()).toLowerCase();
            
            // 5. Validate checksum (if client provided one)
            String normalizedExpected = normalizeChecksum(expectedChecksum);
            if (normalizedExpected != null && !calculatedChecksum.equalsIgnoreCase(normalizedExpected)) {
                throw new IllegalArgumentException("Checksum mismatch");
            }

            // Pre-check Idempotency if already in DB
            Optional<TransferChunkEntity> existingChunk = chunkRepository.findByTransferIdAndChunkIndex(transferId, chunkIndex);
            if (existingChunk.isPresent()) {
                if (!existingChunk.get().getChecksum().equalsIgnoreCase(calculatedChecksum)) {
                    throw new IllegalStateException("Chunk already exists with different content");
                }
                // Identical retry => deterministic idempotent success
                return;
            }

            // 6. Persist bytes to storage provider using checksum-isolated key
            try (InputStream is = new FileInputStream(tempFile.toFile())) {
                chunkStorage.putChunk(transferId, chunkIndex, calculatedChecksum, is, receivedSize);
            }

            // 7. & 8. Mark AVAILABLE and Persist metadata transactionally using DB constraint as concurrency backstop
            TransferChunkEntity chunkEntity = new TransferChunkEntity();
            chunkEntity.setTransferId(transferId);
            chunkEntity.setChunkIndex(chunkIndex);
            chunkEntity.setSize(receivedSize);
            chunkEntity.setChecksum(calculatedChecksum);
            chunkEntity.setStorageKey("transfers/" + transferId + "/chunks/" + String.format("%06d_%s", chunkIndex, calculatedChecksum));
            chunkEntity.setUploadedAt(Instant.now());

            try {
                chunkRepository.saveAndFlush(chunkEntity);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                // Concurrent insert occurred — resolve conflict authoritatively through the DB unique constraint
                Optional<TransferChunkEntity> winningChunk = chunkRepository.findByTransferIdAndChunkIndex(transferId, chunkIndex);
                if (winningChunk.isPresent() && winningChunk.get().getChecksum().equalsIgnoreCase(calculatedChecksum)) {
                    // Same verified checksum -> deterministic idempotent success (winner owns the matching object)
                    return;
                } else {
                    // Different checksum -> CHUNK_CONFLICT: delete only THIS losing request's staged object
                    try {
                        chunkStorage.deleteChunk(transferId, chunkIndex, calculatedChecksum);
                    } catch (Exception ignored) {}
                    throw new IllegalStateException("Chunk already exists with different content");
                }
            } catch (Exception ex) {
                // Storage succeeded but metadata save failed — perform best-effort cleanup of orphan storage object
                try {
                    chunkStorage.deleteChunk(transferId, chunkIndex, calculatedChecksum);
                } catch (Exception ignored) {}
                throw ex;
            }

            if (entity.getStatus() == TransferStatus.CREATED) {
                entity.setStatus(TransferStatus.UPLOADING);
                transferRepository.save(entity);
            }

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public TransferChunkEntity getChunkInfo(String transferId, int chunkIndex, String token) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
        Transfer domain = toDomain(entity);
        domain.checkAccess(token, Instant.now());
        
        if (!domain.getAvailableChunkIndexes().contains(chunkIndex)) {
            throw new ChunkNotAvailableException("Chunk " + chunkIndex + " is not available");
        }
        
        return chunkRepository.findByTransferIdAndChunkIndex(transferId, chunkIndex)
            .orElseThrow(() -> new ChunkNotAvailableException("Chunk metadata missing"));
    }

    public InputStream getChunkStream(String transferId, int chunkIndex, String token) throws Exception {
        TransferChunkEntity chunkInfo = getChunkInfo(transferId, chunkIndex, token); // Validates existence and access
        return chunkStorage.getChunk(transferId, chunkIndex, chunkInfo.getChecksum());
    }

    @Transactional
    public void completeTransfer(String transferId) {
        TransferEntity entity = transferRepository.findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("Transfer not found"));
            
        Transfer domain = toDomain(entity);
        domain.complete(Instant.now());
        saveDomain(domain);
    }
}
