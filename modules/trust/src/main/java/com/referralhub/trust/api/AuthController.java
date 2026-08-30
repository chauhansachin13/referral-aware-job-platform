package com.referralhub.trust.api;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.trust.auth.Account;
import com.referralhub.trust.auth.AccountStore;
import com.referralhub.trust.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AccountStore accounts;

    public AuthController(AuthService authService, AccountStore accounts) {
        this.authService = authService;
        this.accounts = accounts;
    }

    public record RegisterRequest(@NotBlank String displayName,
                                  @NotBlank @Email String email,
                                  @NotBlank @Size(min = 12, max = 200) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RegisteredView(UUID userId) {
    }

    public record MeView(UUID userId, String email, String displayName, List<String> roles) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredView register(@Valid @RequestBody RegisterRequest request) {
        return new RegisteredView(
                authService.register(request.displayName(), request.email(), request.password()));
    }

    @PostMapping("/login")
    public AuthService.Token login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    /** Confirms who the bearer token says you are; the console uses it after a page reload. */
    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Account account = accounts.findById(userId)
                .orElseThrow(() -> new NotFoundException("Account", userId));
        return new MeView(account.id(), account.email(), account.displayName(), account.roles());
    }
}
