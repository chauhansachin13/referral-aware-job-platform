package com.referralhub.ingestion.api;

import com.referralhub.ingestion.board.CompanyBoard;
import java.time.Instant;
import java.util.UUID;

public record BoardView(
        UUID id,
        UUID companyId,
        String companyName,
        String source,
        String boardToken,
        boolean enabled,
        long crawlIntervalSeconds,
        Instant lastCrawledAt,
        Instant lastChangedAt,
        int consecutiveUnchanged,
        double observedPostingsPerDay) {

    public static BoardView of(CompanyBoard board) {
        return new BoardView(board.id(), board.companyId(), board.companyName(), board.source(),
                board.boardToken(), board.enabled(), board.crawlInterval().toSeconds(),
                board.lastCrawledAt(), board.lastChangedAt(), board.consecutiveUnchanged(),
                board.observedPostingsPerDay());
    }
}
