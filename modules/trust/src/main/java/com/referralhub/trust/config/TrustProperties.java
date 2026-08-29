package com.referralhub.trust.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "referralhub.trust")
public class TrustProperties {

    /** How long a completed verification stays good before re-verification is required. */
    private Duration verificationValidity = Duration.ofDays(90);

    /** How long an issued code is usable. */
    private Duration otpValidity = Duration.ofMinutes(10);

    /** Wrong codes tolerated before the verification is revoked. */
    private int maxOtpAttempts = 5;

    /** Referral requests one seeker may send per rolling day. */
    private int seekerDailyRequestCap = 10;

    /** Requests one referrer may hold in ACCEPTED at once. */
    private int referrerConcurrentCapacity = 5;

    /** How long a referrer has to act before a request expires. */
    private Duration requestExpiry = Duration.ofDays(7);

    public Duration getVerificationValidity() {
        return verificationValidity;
    }

    public void setVerificationValidity(Duration verificationValidity) {
        this.verificationValidity = verificationValidity;
    }

    public Duration getOtpValidity() {
        return otpValidity;
    }

    public void setOtpValidity(Duration otpValidity) {
        this.otpValidity = otpValidity;
    }

    public int getMaxOtpAttempts() {
        return maxOtpAttempts;
    }

    public void setMaxOtpAttempts(int maxOtpAttempts) {
        this.maxOtpAttempts = maxOtpAttempts;
    }

    public int getSeekerDailyRequestCap() {
        return seekerDailyRequestCap;
    }

    public void setSeekerDailyRequestCap(int seekerDailyRequestCap) {
        this.seekerDailyRequestCap = seekerDailyRequestCap;
    }

    public int getReferrerConcurrentCapacity() {
        return referrerConcurrentCapacity;
    }

    public void setReferrerConcurrentCapacity(int referrerConcurrentCapacity) {
        this.referrerConcurrentCapacity = referrerConcurrentCapacity;
    }

    public Duration getRequestExpiry() {
        return requestExpiry;
    }

    public void setRequestExpiry(Duration requestExpiry) {
        this.requestExpiry = requestExpiry;
    }
}
