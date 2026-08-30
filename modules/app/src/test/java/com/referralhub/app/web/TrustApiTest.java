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
import com.referralhub.trust.api.TrustController;
import com.referralhub.trust.capacity.ReferrerCapacity;
import com.referralhub.trust.capacity.SeekerQuota;
import com.referralhub.trust.reputation.ReputationScore;
import com.referralhub.trust.verify.VerificationService;
import com.referralhub.trust.verify.VerificationStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = TrustController.class)
@Import(SecurityConfig.class)
class TrustApiTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000007c0");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VerificationService verificationService;
    @MockitoBean
    private VerificationStore store;
    @MockitoBean
    private SeekerQuota seekerQuota;
    @MockitoBean
    private ReferrerCapacity referrerCapacity;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(UUID userId, String... roles) {
        SimpleGrantedAuthority[] authorities =
                java.util.Arrays.stream(roles.length == 0 ? new String[] {"ROLE_USER"} : roles)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new);
        return jwt().jwt(builder -> builder.subject(userId.toString())).authorities(authorities);
    }

    @Test
    @DisplayName("verification is refused without a token")
    void verificationRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/trust/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"%s\",\"workEmail\":\"a@acme.com\"}"
                                .formatted(COMPANY)))
                .andExpect(status().isUnauthorized());

        verify(verificationService, never()).startVerification(any(), any(), anyString());
    }

    @Test
    @DisplayName("you can only start verification for yourself")
    void verificationIsAlwaysForTheCaller() throws Exception {
        mvc.perform(post("/api/v1/trust/verifications")
                        .with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        // A userId in the body has nowhere to go: the request record has no such
                        // field, and the identity is read from the token.
                        .content("""
                                {"userId":"%s","companyId":"%s","workEmail":"sachin@acme.com"}"""
                                .formatted(OTHER, COMPANY)))
                .andExpect(status().isAccepted());

        verify(verificationService).startVerification(eq(USER), eq(COMPANY), eq("sachin@acme.com"));
    }

    @Test
    @DisplayName("the response never contains the code, whatever else it contains")
    void responseNeverLeaksTheCode() throws Exception {
        mvc.perform(post("/api/v1/trust/verifications")
                        .with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"%s\",\"workEmail\":\"sachin@acme.com\"}"
                                .formatted(COMPANY)))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("a consumer email address is refused with the reason")
    void consumerDomainIsRefused() throws Exception {
        org.mockito.Mockito.doThrow(new ConflictException(
                        "That address is not on Acme's domain (acme.com)"))
                .when(verificationService).startVerification(any(), any(), anyString());

        mvc.perform(post("/api/v1/trust/verifications")
                        .with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"%s\",\"workEmail\":\"sachin@gmail.com\"}"
                                .formatted(COMPANY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    @DisplayName("a code that is not six digits never reaches the service")
    void malformedCodeIsRejected() throws Exception {
        for (String bad : new String[] {"12345", "1234567", "abcdef", ""}) {
            mvc.perform(post("/api/v1/trust/verifications/confirm")
                            .with(as(USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"companyId\":\"%s\",\"code\":\"%s\"}".formatted(COMPANY, bad)))
                    .andExpect(status().isBadRequest());
        }
        verify(verificationService, never()).confirm(any(), any(), anyString());
    }

    @Test
    @DisplayName("a correct code returns the lease expiry")
    void confirmReturnsExpiry() throws Exception {
        Instant expiry = Instant.parse("2026-11-27T00:00:00Z");
        when(verificationService.confirm(USER, COMPANY, "123456")).thenReturn(expiry);

        mvc.perform(post("/api/v1/trust/verifications/confirm")
                        .with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"%s\",\"code\":\"123456\"}".formatted(COMPANY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-27T00:00:00Z"));
    }

    @Test
    @DisplayName("standing exposes reputation and both remaining budgets")
    void standingExposesBudgets() throws Exception {
        when(store.countersFor(USER))
                .thenReturn(new ReputationScore.Counters(100, 95, 60, 55, 3));
        when(seekerQuota.remaining(USER)).thenReturn(7);
        when(referrerCapacity.remaining(USER)).thenReturn(2);

        mvc.perform(get("/api/v1/trust/standing").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingDailyRequests").value(7))
                .andExpect(jsonPath("$.remainingReferralCapacity").value(2))
                .andExpect(jsonPath("$.reputation").value(org.hamcrest.Matchers.greaterThan(0.5)))
                .andExpect(jsonPath("$.responseRate").value(org.hamcrest.Matchers.lessThan(0.95)));
    }

    @Test
    @DisplayName("someone else's standing is administrator-only: it is a signal about a person")
    void othersStandingIsRestricted() throws Exception {
        when(store.countersFor(any())).thenReturn(ReputationScore.Counters.empty());

        mvc.perform(get("/api/v1/trust/users/" + OTHER + "/standing").with(as(USER)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/trust/users/" + OTHER + "/standing")
                        .with(as(USER, "ROLE_USER", "ROLE_ADMIN")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/trust/users/" + USER + "/standing").with(as(USER)))
                .andExpect(status().isOk());
    }
}
