package me.desair.spring.transfer.application.exception;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(String message) {
        super(message);
    }
}
