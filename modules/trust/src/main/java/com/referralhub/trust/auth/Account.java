package com.referralhub.trust.auth;

import java.util.List;
import java.util.UUID;

/** A user as the authentication layer sees them. */
public record Account(UUID id, String email, String displayName, String passwordHash,
                      List<String> roles) {

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}
