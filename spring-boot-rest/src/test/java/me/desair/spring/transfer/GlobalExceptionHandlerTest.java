package me.desair.spring.transfer;

import me.desair.spring.transfer.api.ErrorResponse;
import me.desair.spring.transfer.api.GlobalExceptionHandler;
import me.desair.spring.transfer.application.exception.ChunkNotAvailableException;
import me.desair.spring.transfer.application.exception.TransferNotFoundException;
import me.desair.spring.transfer.domain.TransferDomainException;
import me.desair.spring.transfer.domain.TransferExpiredException;
import me.desair.spring.transfer.infrastructure.storage.StorageFileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleTransferNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleTransferNotFound(new TransferNotFoundException("Transfer missing"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("TRANSFER_NOT_FOUND", response.getBody().getCode());
        assertEquals("The requested transfer was not found.", response.getBody().getMessage());
    }

    @Test
    void handleChunkNotAvailable() {
        ResponseEntity<ErrorResponse> response = handler.handleChunkNotAvailable(new ChunkNotAvailableException("Chunk 0 not ready"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("CHUNK_NOT_AVAILABLE", response.getBody().getCode());
        assertEquals("The requested chunk is not available.", response.getBody().getMessage());
    }

    @Test
    void handleStorageFileNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleStorageFileNotFound(new StorageFileNotFoundException("File missing from disk"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("STORAGE_FAILURE", response.getBody().getCode());
        assertEquals("A required storage object is missing.", response.getBody().getMessage());
    }

    @Test
    void handleTransferExpired() {
        ResponseEntity<ErrorResponse> response = handler.handleTransferExpired(new TransferExpiredException("Expired"));
        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals("TRANSFER_EXPIRED", response.getBody().getCode());
        assertEquals("This transfer has expired.", response.getBody().getMessage());
    }

    @Test
    void handleDomainException() {
        ResponseEntity<ErrorResponse> response1 = handler.handleDomainException(new TransferDomainException("Invalid chunk size"));
        assertEquals(HttpStatus.BAD_REQUEST, response1.getStatusCode());
        assertEquals("INVALID_REQUEST", response1.getBody().getCode());

        ResponseEntity<ErrorResponse> response2 = handler.handleDomainException(new TransferDomainException("Invalid share token"));
        assertEquals(HttpStatus.FORBIDDEN, response2.getStatusCode());
        assertEquals("FORBIDDEN", response2.getBody().getCode());
    }
}
