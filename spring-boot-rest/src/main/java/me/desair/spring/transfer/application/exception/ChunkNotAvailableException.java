package me.desair.spring.transfer.application.exception;

public class ChunkNotAvailableException extends RuntimeException {
    public ChunkNotAvailableException(String message) {
        super(message);
    }
}
