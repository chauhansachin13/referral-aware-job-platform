package com.referralhub.common.error;

public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super("conflict", message);
    }
}
