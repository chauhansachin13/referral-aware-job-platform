package com.referralhub.ingestion.board;

import java.util.UUID;

/** A company, as downstream modules read it. */
public record CompanyRecord(UUID id, String name, String slug, String emailDomain, String careersUrl) {
}
