package me.desair.spring.transfer.api;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import jakarta.validation.constraints.Max;

@Data
public class CreateTransferRequest {
    @NotBlank(message = "fileName must not be blank")
    private String fileName;
    
    @Positive(message = "fileSize must be positive")
    @Max(value = 53687091200L, message = "fileSize must not exceed 50GB")
    private long fileSize;
    
    private String contentType;
}
