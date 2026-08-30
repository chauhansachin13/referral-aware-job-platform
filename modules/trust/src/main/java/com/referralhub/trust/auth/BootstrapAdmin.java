package com.referralhub.trust.auth;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, if one is configured and none exists.
 *
 * <p>Somebody has to be able to register the first ATS board. The alternatives are worse: a
 * hardcoded account ships a known credential, and an open "make me an admin" endpoint is not an
 * alternative at all. This runs only when both properties are supplied, and only when the table
 * holds no administrator, so it cannot silently re-create a deliberately removed account.
 */
@Component
public class BootstrapAdmin {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AccountStore accounts;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public BootstrapAdmin(AccountStore accounts, PasswordEncoder passwordEncoder,
                          AuthProperties properties) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createIfConfigured() {
        String email = properties.getBootstrapAdminEmail();
        String password = properties.getBootstrapAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (accounts.anyAdminExists()) {
            log.debug("An administrator already exists; bootstrap skipped");
            return;
        }
        accounts.findByEmail(email).ifPresentOrElse(
                account -> {
                    accounts.setPassword(account.id(), passwordEncoder.encode(password));
                    log.info("Bootstrap: set a password on the existing account {}", account.id());
                },
                () -> {
                    accounts.create("Administrator", email, passwordEncoder.encode(password),
                            List.of("USER", "ADMIN"));
                    log.info("Bootstrap: created the initial administrator {}", email);
                });
    }
}
