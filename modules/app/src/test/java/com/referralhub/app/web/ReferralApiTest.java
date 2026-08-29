package com.referralhub.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.referralhub.referral.state.ReferralState;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReferralController.class)
class ReferralApiTest {

    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000r1"
            .replace("r", "a"));
    private static final UUID SEEKER = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID REFERRER = UUID.fromString("00000000-0000-0000-0000-000000000502");
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

    private static ReferralRequest request(ReferralState state) {
        Instant now = Instant.parse("2026-08-20T10:00:00Z");
        return new ReferralRequest(REQUEST, SEEKER, REFERRER, JOB, COMPANY, null, state,
                "please", null, now, now, now.plus(Duration.ofDays(7)), null, null, null);
    }

    @Test
    @DisplayName("creating a request forwards the Idempotency-Key to the domain")
    void forwardsIdempotencyKey() throws Exception {
        when(referrals.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(request(ReferralState.REQUESTED));

        mvc.perform(post("/api/v1/referrals")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seekerId":"%s","canonicalJobId":"%s","companyId":"%s",
                                 "message":"please"}""".formatted(SEEKER, JOB, COMPANY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REQUESTED"));

        verify(referrals).request(eq(SEEKER), eq(JOB), eq(COMPANY), eq(null), eq("please"),
                eq("abc-123"));
    }

    @Test
    @DisplayName("the key is optional, because not every client has one")
    void idempotencyKeyIsOptional() throws Exception {
        when(referrals.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(request(ReferralState.REQUESTED));

        mvc.perform(post("/api/v1/referrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seekerId":"%s","canonicalJobId":"%s","companyId":"%s"}"""
                                .formatted(SEEKER, JOB, COMPANY)))
                .andExpect(status().isCreated());

        verify(referrals).request(any(), any(), any(), any(), any(), eq(null));
    }

    @Test
    @DisplayName("an illegal transition surfaces as 422 with the allowed moves named")
    void illegalTransitionIsUnprocessable() throws Exception {
        when(referrals.submit(any(), any(), any())).thenThrow(
                new com.referralhub.referral.state.IllegalTransitionException(
                        ReferralState.REQUESTED, ReferralState.SUBMITTED));

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"%s\"}".formatted(REFERRER)))
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

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"%s\"}".formatted(REFERRER)))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seekerId":"%s","canonicalJobId":"%s","companyId":"%s"}"""
                                .formatted(SEEKER, JOB, COMPANY)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5400"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    @DisplayName("a resume link is refused before acceptance")
    void resumeLinkGatedOnState() throws Exception {
        when(referrals.mintResumeDownloadUrl(any(), any())).thenThrow(new ConflictException(
                "The resume is released once you accept the request; this one is REQUESTED"));

        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/resume-link")
                        .param("referrerId", REFERRER.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a resume download is never cacheable and always an attachment")
    void resumeDownloadIsNotCacheable() throws Exception {
        when(referrals.readResume("tok")).thenReturn(new ReferralService.ResumePayload(
                "sachin-cv.pdf", "application/pdf", "PDF-BYTES".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/v1/referrals/resume").param("token", "tok"))
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

        mvc.perform(get("/api/v1/referrals/resume").param("token", "stale"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_resume_token"));
    }

    @Test
    @DisplayName("deleting a resume is a hard delete and answers 204")
    void resumeDeletionIsNoContent() throws Exception {
        UUID resume = UUID.randomUUID();

        mvc.perform(delete("/api/v1/referrals/resumes/" + resume))
                .andExpect(status().isNoContent());

        verify(resumes).hardDelete(resume);
    }

    @Test
    @DisplayName("a missing referral is a 404")
    void missingReferralIsNotFound() throws Exception {
        when(referrals.load(any())).thenThrow(new NotFoundException("Referral request", REQUEST));

        mvc.perform(get("/api/v1/referrals/" + REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("a body with no actor is refused before the service is touched")
    void missingActorIsRejected() throws Exception {
        mvc.perform(post("/api/v1/referrals/" + REQUEST + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }
}
