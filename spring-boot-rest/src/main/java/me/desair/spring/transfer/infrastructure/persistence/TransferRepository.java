package me.desair.spring.transfer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<TransferEntity, String> {
    List<TransferEntity> findByExpiresAtBefore(Instant expiresAt);
    Optional<TransferEntity> findByTransferCode(String transferCode);
}
