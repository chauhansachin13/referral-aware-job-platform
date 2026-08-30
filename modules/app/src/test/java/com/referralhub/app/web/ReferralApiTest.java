package com.referralhub.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.referralhub.app.SecurityConfig;
import com.referralhub.common.error.ConflictException;
import com.referralhub.common.error.NotFoundException;
import com.referralhub.common.error.RateLimitedException;
import com.referralhub.referral.ReferralRequest;
import com.referralhub.referral.ReferralRequestStore;
import com.referralhub.referral.ReferralService;
import com.referralhub.referral.api.ReferralController;
import com.referralhub.referral.match.ReferralMatchingService;
import com.referralhub.referral.resume.ResumeAccessToken;
import com.referralhub.referral.resume.ResumeStorage;
import com.referralhub.referral.resume.StoredResume;
import com.referralhub.referral.state.ReferralState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The referral HTTP contract, now including who is allowed to call it.
 *
 * <p>The real {@link SecurityConfig} is imported rather than stubbed away. A web-slice test that
 * disables security proves the handler works for a caller who was never checked, which is
 * exactly the property that was wrong before authentication existed.
 */
@WebMvcTest(controllers = ReferralController.class)
@Import(SecurityConfig.class)
class ReferralApiTest {

    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
    private static final UUID SEEKER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID REFERRER = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000005a0");
    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000005c0");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReferralService referrals;
    @MockitoBean
    private ReferralRequestStore store;
    @MockitoBean
    private ResumeStorage resumes;
    @MockitoBean
    private ReferralMatchingService matching;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(UUID userId) {
        return jwt().jwt(builder -> builder.subject(userId.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_USER"));
    }

    private static ReferralRequest request(ReferralState state) {
        Instant now = Instant.parse("2026-08-20T10:00:00Z");
        return new ReferralRequest(REQUEST, SEEKER, REFERRER, JOB, COMPANY, null, state,
                "please", null, now, now, now.plus(Duration.ofDays(7)), null, null, null);
    }

    // --------------------------------------------------------------------------------
    // Authentication and authorization
    // --------------------------------------------------------------------------------

    @Test
    @DisplayName("every referral endpoint refuses an anonymous caller")
    void anonymousCallersAreRefused() throws Exception {
        mvc.perform(get("/api/v1/referrals/" + REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/referrals/mine")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/accept"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/referrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalJobId\":\"%s\",\"companyId\":\"%s\"}"
                                .formatted(JOB, COMPANY)))
                .andExpect(status().isUnauthorized());

        verify(referrals, never()).accept(any(), any(), any());
    }

    @Test
    @DisplayName("a 401 carries the standard error shape, not an empty body")
    void unauthenticatedResponseIsStructured() throws Exception {
        mvc.perform(get("/api/v1/referrals/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("the acting identity comes from the token, not from the request body")
    void actorComesFromTheToken() throws Exception {
        when(referrals.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(request(ReferralState.REQUESTED));

        // The body names a different user; it must be ignored entirely.
        mvc.perform(post("/api/v1/referrals")
                        .with(as(SEEKER))
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seekerId":"%s","canonicalJobId":"%s","companyId":"%s",
                                 "message":"please"}""".formatted(STRANGER, JOB, COMPANY)))
                .andExpect(status().isCreated());

        verify(referrals).request(eq(SEEKER), eq(JOB), eq(COMPANY), eq(null), eq("please"),
                eq("abc-123"));
    }

    @Test
    @DisplayName("a third party cannot read a referral they are not party to")
    void nonParticipantsCannotRead() throws Exception {
        when(referrals.load(REQUEST)).thenReturn(request(ReferralState.ACCEPTED));

        mvc.perform(get("/api/v1/referrals/" + REQUEST).with(as(STRANGER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That referral request is not yours"));

        mvc.perform(get("/api/v1/referrals/" + REQUEST).with(as(SEEKER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/referrals/" + REQUEST).with(as(REFERRER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a resume can only be deleted by whoever owns it")
    void resumeDeletionChecksOwnership() throws Exception {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findMetadata(resumeId)).thenReturn(Optional.of(new StoredResume(
                resumeId, SEEKER, "resumes/x.enc", "cv.pdf", "application/pdf", 10, "hash",
                "iv", "key-v1", Instant.now())));

        mvc.perform(delete("/api/v1/referrals/resumes/" + resumeId).with(as(STRANGER)))
                .andExpect(status().isConflict());
        verify(resumes, never()).hardDelete(any());

        mvc.perform(delete("/api/v1/referrals/resumes/" + resumeId).with(as(SEEKER)))
                .andExpect(status().isNoContent());
        verify(resumes).hardDelete(resumeId);
    }

    // --------------------------------------------------------------------------------
    // Contract
    // --------------------------------------------------------------------------------

    @Test
    @DisplayName("the Idempotency-Key is optional, because not every client has one")
    void idempotencyKeyIsOptional() throws Exception {
        when(referrals.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(request(ReferralState.REQUESTED));

        mvc.perform(post("/api/v1/referrals")
                        .with(as(SEEKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalJobId\":\"%s\",\"companyId\":\"%s\"}"
                                .formatted(JOB, COMPANY)))
                .andExpect(status().isCreated());

        verify(referrals).request(any(), any(), any(), any(), any(), eq(null));
    }

    @Test
    @DisplayName("an illegal transition surfaces as 422 with the allowed moves named")
    void illegalTransitionIsUnprocessable() throws Exception {
        when(referrals.submit(any(), any(), any())).thenThrow(
                new com.referralhub.referral.state.IllegalTransitionException(
                        ReferralState.REQUESTED, ReferralState.SUBMITTED));

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/submit").with(as(REFERRER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("illegal_transition"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ACCEPTED")));
    }

    @Test
    @DisplayName("acting on someone else's referral is a 409, not a 403 that leaks existence")
    void takenReferralIsAConflict() throws Exception {
        when(referrals.accept(any(), any(), any()))
                .thenThrow(new ConflictException("This referral was accepted by someone else"));

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/accept").with(as(REFERRER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    @DisplayName("exhausting the daily quota returns 429 with a usable Retry-After")
    void quotaExhaustionIsRateLimited() throws Exception {
        when(referrals.request(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RateLimitedException("Daily referral request limit of 10 reached",
                        Duration.ofMinutes(90)));

        mvc.perform(post("/api/v1/referrals")
                        .with(as(SEEKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalJobId\":\"%s\",\"companyId\":\"%s\"}"
                                .formatted(JOB, COMPANY)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5400"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    @DisplayName("a resume link is refused before acceptance")
    void resumeLinkGatedOnState() throws Exception {
        when(referrals.mintResumeDownloadUrl(any(), any())).thenThrow(new ConflictException(
                "The resume is released once you accept the request; this one is REQUESTED"));

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/resume-link").with(as(REFERRER)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the download endpoint is the token's, not the session's")
    void resumeDownloadIsAuthorizedByTheTokenItself() throws Exception {
        when(referrals.readResume("tok")).thenReturn(new ReferralService.ResumePayload(
                "sachin-cv.pdf", "application/pdf", "PDF-BYTES".getBytes(StandardCharsets.UTF_8)));

        // Signed, short-lived and bound to one referral, so it stands on its own — but the route
        // still sits behind authentication, so a leaked link alone is not enough.
        mvc.perform(get("/api/v1/referrals/resume").param("token", "tok").with(as(REFERRER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes("PDF-BYTES".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("an expired download token is a domain error, not a stack trace")
    void expiredTokenIsRejected() throws Exception {
        when(referrals.readResume(any())).thenThrow(
                new ResumeAccessToken.InvalidTokenException("Download link has expired"));

        mvc.perform(get("/api/v1/referrals/resume").param("token", "stale").with(as(REFERRER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_resume_token"));
    }

    @Test
    @DisplayName("a missing referral is a 404")
    void missingReferralIsNotFound() throws Exception {
        when(referrals.load(any())).thenThrow(new NotFoundException("Referral request", REQUEST));

        mvc.perform(get("/api/v1/referrals/" + REQUEST).with(as(SEEKER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("a create with no job id is refused before the service is touched")
    void missingJobIdIsRejected() throws Exception {
        mvc.perform(post("/api/v1/referrals")
                        .with(as(SEEKER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));

        verify(referrals, never()).request(any(), any(), any(), any(), any(), any());
    }
}
