package me.desair.spring.transfer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransferChunkRepository extends JpaRepository<TransferChunkEntity, Long> {
    List<TransferChunkEntity> findByTransferIdOrderByChunkIndexAsc(String transferId);
    Optional<TransferChunkEntity> findByTransferIdAndChunkIndex(String transferId, int chunkIndex);
    void deleteByTransferId(String transferId);
}
