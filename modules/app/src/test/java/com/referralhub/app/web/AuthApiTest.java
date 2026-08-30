package com.referralhub.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.referralhub.app.SecurityConfig;
import com.referralhub.common.error.ConflictException;
import com.referralhub.trust.api.AuthController;
import com.referralhub.trust.auth.Account;
import com.referralhub.trust.auth.AccountStore;
import com.referralhub.trust.auth.AuthService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthApiTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000801");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private AccountStore accounts;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("registration and login are public, or nobody could ever get a token")
    void registrationAndLoginArePublic() throws Exception {
        when(authService.register(anyString(), anyString(), anyString())).thenReturn(USER);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Sachin","email":"sachin@example.com",
                                 "password":"a-sufficiently-long-password"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER.toString()));
    }

    @Test
    @DisplayName("a short password is refused before it is ever hashed")
    void shortPasswordsAreRefused() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Sachin","email":"sachin@example.com",
                                 "password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        verify(authService, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an address that is already taken is a conflict")
    void duplicateRegistrationIsAConflict() throws Exception {
        when(authService.register(anyString(), anyString(), anyString()))
                .thenThrow(new ConflictException("An account with that email already exists"));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Sachin","email":"taken@example.com",
                                 "password":"a-sufficiently-long-password"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("login returns a bearer token and its lifetime")
    void loginReturnsAToken() throws Exception {
        when(authService.login(eq("sachin@example.com"), anyString()))
                .thenReturn(new AuthService.Token("a.b.c", "Bearer", 43_200, USER,
                        List.of("USER")));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"sachin@example.com","password":"a-long-password"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a.b.c"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(43_200))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    @DisplayName("bad credentials are a 401 that says nothing about which half was wrong")
    void badCredentialsAreOpaque() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new BadCredentialsException("Email or password is incorrect"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("no such user"))));
    }

    @Test
    @DisplayName("/me needs a token and reports the account it belongs to")
    void meRequiresAToken() throws Exception {
        when(accounts.findById(USER)).thenReturn(Optional.of(new Account(
                USER, "sachin@example.com", "Sachin", "hash", List.of("USER"))));

        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/auth/me")
                        .with(jwt().jwt(builder -> builder.subject(USER.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sachin@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }
}
