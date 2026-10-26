package me.desair.spring.transfer.domain;

public class TransferExpiredException extends RuntimeException {
    public TransferExpiredException(String message) {
        super(message);
    }
}
