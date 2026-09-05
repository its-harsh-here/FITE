package me.desair.spring.transfer.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkUploadUrlResponse {
    private String uploadUrl;
    private String storageKey;
    private Map<String, String> headers;
    private Instant expiresAt;
}
