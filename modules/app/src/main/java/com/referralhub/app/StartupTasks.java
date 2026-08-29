package com.referralhub.app;

import com.referralhub.referral.resume.ResumeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Best-effort provisioning of downstream resources on boot.
 *
 * <p>Failures are logged, not fatal. An application that crash-loops because MinIO took ten
 * seconds longer than Postgres to come up is worse than one that serves everything else and
 * fails resume uploads with a clear error until the bucket exists.
 */
@Component
public class StartupTasks {

    private static final Logger log = LoggerFactory.getLogger(StartupTasks.class);

    private final ResumeStorage resumes;

    public StartupTasks(ResumeStorage resumes) {
        this.resumes = resumes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            resumes.ensureBucket();
        } catch (Exception e) {
            log.warn("Could not ensure the resume bucket on startup: {}", e.toString());
        }
    }
}
