package com.referralhub.ingestion.adapter;

public class AdapterParseException extends RuntimeException {

    public AdapterParseException(String message) {
        super(message);
    }

    public AdapterParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
