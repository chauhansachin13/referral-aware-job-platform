package com.referralhub.trust.api;

import com.referralhub.common.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Authentication failures, mapped where the module that raises them can see them.
 *
 * <p>The shared handler in {@code common} cannot catch these: it would have to depend on Spring
 * Security, which would put the framework on the classpath of every module including the ones
 * with no HTTP surface at all.
 *
 * <p>Ordered ahead of the shared advice, which otherwise swallows a
 * {@link BadCredentialsException} into its catch-all and answers 500. A failed login returning
 * "Something went wrong" is both wrong and actively misleading — it reads as an outage.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException e) {
        // Deliberately not logged at warn with the address: a log full of attempted emails is a
        // list of this platform's users waiting to leak.
        log.debug("Rejected a login attempt");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("invalid_credentials", "Email or password is incorrect"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("unauthenticated", "Authentication is required"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "Your account may not perform this action"));
    }
}
