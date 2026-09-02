package me.desair.spring.transfer.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import me.desair.spring.transfer.domain.TransferStatus;

@Entity
@Data
@NoArgsConstructor
public class TransferEntity {
    @Id
    private String transferId;

    private String shareToken;
    private String transferCode;
    private String fileName;
    private String contentType;
    private long fileSize;
    private long chunkSize;
    private int totalChunks;
    
    @Enumerated(EnumType.STRING)
    private TransferStatus status;
    
    private Instant createdAt;
    private Instant expiresAt;
}
