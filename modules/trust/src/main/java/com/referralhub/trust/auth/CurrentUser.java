package com.referralhub.trust.auth;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller.
 *
 * <p>Exists so that no controller ever takes an actor id from the request body again. Before
 * authentication, every referral endpoint believed whatever {@code actorId} it was handed, which
 * meant any caller could accept somebody else's referral or redeem a resume link issued to
 * another person.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return UUID.fromString(jwt.getSubject());
    }

    public static UUID idOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
    }
}
