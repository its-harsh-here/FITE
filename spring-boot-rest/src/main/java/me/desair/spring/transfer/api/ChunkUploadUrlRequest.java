package me.desair.spring.transfer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkUploadUrlRequest {

    @NotBlank(message = "Checksum is required")
    private String checksum;

    private String md5Checksum;

    @Positive(message = "Size must be positive")
    private long size;
}
