package com.referralhub.ingestion.adapter;

import com.referralhub.ingestion.board.CompanyBoard;
import java.net.URI;
import java.util.List;

/**
 * Everything ATS-specific, and nothing else.
 *
 * <p>The pipeline — scheduling, rate limiting, conditional fetching, hashing, persistence,
 * event emission — knows only this interface. Adding Workday or SmartRecruiters is a new class
 * and a row in {@code company_board}; it is not a change to any of the code that decides
 * <em>when</em> to fetch or <em>whether</em> anything changed.
 */
public interface SourceAdapter {

    /** Stable identifier stored on every posting, e.g. {@code greenhouse}. */
    String source();

    /** The public board endpoint for this company. */
    URI boardUri(CompanyBoard board);

    /**
     * Parses a successful response body.
     *
     * @throws AdapterParseException if the body is not the shape this adapter expects — the
     *         pipeline treats that as a crawl failure rather than as "the board is now empty",
     *         which would otherwise expire every live posting for the company.
     */
    List<ParsedPosting> parse(String body, CompanyBoard board);

    /** Host used for per-domain rate limiting; derived from the board URI by default. */
    default String rateLimitHost(CompanyBoard board) {
        return boardUri(board).getHost();
    }
}
