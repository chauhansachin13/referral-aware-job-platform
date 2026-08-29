package com.referralhub.referral;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot entry point for this module's integration tests. */
@SpringBootApplication(scanBasePackages = {
        "com.referralhub.common",
        "com.referralhub.ingestion.board",
        "com.referralhub.dedup",
        "com.referralhub.trust",
        "com.referralhub.referral"})
public class ReferralTestApplication {
}
