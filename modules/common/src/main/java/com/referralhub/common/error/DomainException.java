package com.referralhub.common.error;

/** Base type for failures that are the caller's fault, not the system's. */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
