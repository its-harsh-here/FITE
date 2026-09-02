package me.desair.spring.transfer.application;

import me.desair.spring.transfer.infrastructure.storage.ChunkStorage;
import me.desair.spring.transfer.infrastructure.persistence.TransferChunkRepository;
import me.desair.spring.transfer.infrastructure.persistence.TransferEntity;
import me.desair.spring.transfer.infrastructure.persistence.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import me.desair.spring.transfer.domain.TransferStatus;

@Service
public class TransferCleanupService {
    private static final Logger LOG = LoggerFactory.getLogger(TransferCleanupService.class);
    
    private final TransferRepository transferRepository;
    private final TransferChunkRepository chunkRepository;
    private final ChunkStorage chunkStorage;

    public TransferCleanupService(TransferRepository transferRepository, TransferChunkRepository chunkRepository, ChunkStorage chunkStorage) {
        this.transferRepository = transferRepository;
        this.chunkRepository = chunkRepository;
        this.chunkStorage = chunkStorage;
    }

    @Scheduled(fixedRateString = "${transfer.cleanup.interval:3600000}")
    @Transactional
    public void cleanupExpiredTransfers() {
        LOG.info("Running expired transfer cleanup...");
        
        // 1. Find expired transfers
        List<TransferEntity> expiredTransfers = transferRepository.findByExpiresAtBefore(Instant.now());
            
        for (TransferEntity transfer : expiredTransfers) {
            try {
                // 2. Reject future access immediately
                if (transfer.getStatus() != TransferStatus.EXPIRED) {
                    transfer.setStatus(TransferStatus.EXPIRED);
                    transferRepository.saveAndFlush(transfer);
                }

                // 3. Delete chunk objects (idempotent, skips if missing)
                chunkStorage.deleteTransfer(transfer.getTransferId());
                
                // 4. Delete chunk metadata
                chunkRepository.deleteByTransferId(transfer.getTransferId());

                // 5. Delete transfer metadata
                transferRepository.delete(transfer);
                
                LOG.info("Cleaned up expired transfer: {}", transfer.getTransferId());
            } catch (Exception e) {
                LOG.error("Failed to cleanup transfer: {}", transfer.getTransferId(), e);
            }
        }
    }
}
