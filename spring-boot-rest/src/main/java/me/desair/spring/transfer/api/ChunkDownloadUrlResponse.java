package me.desair.spring.transfer.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkDownloadUrlResponse {
    private int chunkIndex;
    private long size;
    private String checksum;
    private String downloadUrl;
    private Instant expiresAt;
}
