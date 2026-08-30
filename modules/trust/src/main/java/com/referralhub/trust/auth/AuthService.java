package com.referralhub.trust.auth;

import com.referralhub.common.error.ConflictException;
import com.referralhub.trust.verify.WorkEmails;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login.
 *
 * <p>Tokens are self-issued and symmetrically signed. That is the right weight for a platform
 * with one deployable and no federation: an asymmetric key pair buys the ability to verify
 * tokens without the signing secret, which matters when several services verify what one
 * service issues, and here the issuer and the verifier are the same process.
 *
 * <p>The token carries the user id as its subject and roles as a claim, so an authorization
 * decision needs no database read. The cost is that a role change or a deactivation takes effect
 * only when the token expires — a deliberate trade at a 12 hour TTL, and the reason the TTL is
 * hours rather than weeks.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountStore accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public AuthService(AccountStore accounts,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       AuthProperties properties) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public record Token(String accessToken, String tokenType, long expiresInSeconds, UUID userId,
                        List<String> roles) {
    }

    @Transactional
    public UUID register(String displayName, String email, String rawPassword) {
        if (!WorkEmails.isWellFormed(email)) {
            throw new IllegalArgumentException("Not a valid email address");
        }
        if (rawPassword == null || rawPassword.length() < 12) {
            // A length floor rather than a character-class rule: length is what actually
            // resists an offline attack, and composition rules mostly produce Passw0rd!.
            throw new IllegalArgumentException("Password must be at least 12 characters");
        }

        Optional<Account> existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            Account account = existing.get();
            if (account.hasPassword()) {
                throw new ConflictException("An account with that email already exists");
            }
            // The account was created by the pre-authentication user endpoint; adopt it rather
            // than orphaning whatever referral history it already has.
            accounts.setPassword(account.id(), passwordEncoder.encode(rawPassword));
            return account.id();
        }
        return accounts.create(displayName, email, passwordEncoder.encode(rawPassword),
                List.of("USER"));
    }

    public Token login(String email, String rawPassword) {
        Optional<Account> found = accounts.findByEmail(email);

        // Hash regardless of whether the account exists, so response time does not reveal which
        // addresses are registered.
        String storedHash = found.filter(Account::hasPassword)
                .map(Account::passwordHash)
                .orElse("$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidinv");
        boolean matches = passwordEncoder.matches(rawPassword, storedHash);

        if (found.isEmpty() || !found.get().hasPassword() || !matches) {
            throw new BadCredentialsException("Email or password is incorrect");
        }

        Account account = found.get();
        accounts.recordLogin(account.id());
        return issueToken(account);
    }

    public Token issueToken(Account account) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(account.id().toString())
                .claim("email", account.email())
                .claim("name", account.displayName())
                .claim("roles", account.roles())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                claims)).getTokenValue();

        log.debug("Issued token for {}", account.id());
        return new Token(token, "Bearer", properties.getTokenTtl().toSeconds(), account.id(),
                account.roles());
    }
}
