package com.referralhub.common.error;

import java.time.Instant;
import java.util.List;

/** The single error shape every endpoint returns. */
public record ApiError(String code, String message, List<String> details, Instant timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of(), Instant.now());
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, details, Instant.now());
    }
}
