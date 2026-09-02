package me.desair.spring.transfer.api;

import me.desair.spring.transfer.application.exception.ChunkNotAvailableException;
import me.desair.spring.transfer.infrastructure.storage.StorageException;
import me.desair.spring.transfer.infrastructure.storage.StorageFileNotFoundException;
import me.desair.spring.transfer.application.exception.TransferNotFoundException;
import me.desair.spring.transfer.domain.TransferDomainException;
import me.desair.spring.transfer.domain.TransferExpiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("TRANSFER_NOT_FOUND", "The requested transfer was not found."));
    }

    @ExceptionHandler(ChunkNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleChunkNotAvailable(ChunkNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CHUNK_NOT_AVAILABLE", "The requested chunk is not available."));
    }

    @ExceptionHandler(StorageFileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStorageFileNotFound(StorageFileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("STORAGE_FAILURE", "A required storage object is missing."));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorageException(StorageException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("STORAGE_FAILURE", "Storage operation failed."));
    }

    @ExceptionHandler(TransferExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTransferExpired(TransferExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse("TRANSFER_EXPIRED", "This transfer has expired."));
    }

    @ExceptionHandler(TransferDomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(TransferDomainException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String code = "INVALID_REQUEST";
        String message = "Invalid transfer request.";
        
        if (ex.getMessage() != null && ex.getMessage().contains("Invalid share token")) {
            status = HttpStatus.FORBIDDEN;
            code = "FORBIDDEN";
            message = "Invalid share token.";
        } else if (ex.getMessage() != null && ex.getMessage().contains("Chunk already exists with different content")) {
            status = HttpStatus.CONFLICT;
            code = "CHUNK_CONFLICT";
            message = "Chunk already exists with different content.";
        } else if (ex.getMessage() != null && ex.getMessage().contains("Transfer is not complete")) {
            status = HttpStatus.CONFLICT;
            code = "TRANSFER_NOT_COMPLETE";
            message = "Transfer is not complete; missing chunks.";
        } else if (ex.getMessage() != null && ex.getMessage().contains("Chunk index out of bounds")) {
            status = HttpStatus.BAD_REQUEST;
            code = "INVALID_CHUNK_INDEX";
            message = "Chunk index is out of bounds.";
        }
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        if ("Checksum mismatch".equalsIgnoreCase(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse("CHECKSUM_MISMATCH", "Calculated checksum does not match expected checksum."));
        }
        if (ex.getMessage() != null && ex.getMessage().contains("Invalid chunk size")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("INVALID_CHUNK_SIZE", "Chunk size does not match expected size."));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "Invalid request parameters."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("Chunk already exists with different content")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("CHUNK_CONFLICT", "Chunk already exists with different content."));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_TRANSFER_STATE", "Transfer is in an invalid state for this operation."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred."));
    }
}
