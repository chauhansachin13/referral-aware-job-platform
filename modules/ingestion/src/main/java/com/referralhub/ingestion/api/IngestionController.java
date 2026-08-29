package com.referralhub.ingestion.api;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.pipeline.CrawlOutcome;
import com.referralhub.ingestion.pipeline.CrawlPipeline;
import com.referralhub.ingestion.schedule.CrawlQueue;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator-facing endpoints for the crawler. */
@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {

    private final BoardStore boards;
    private final CrawlQueue queue;
    private final CrawlPipeline pipeline;

    public IngestionController(BoardStore boards, CrawlQueue queue, CrawlPipeline pipeline) {
        this.boards = boards;
        this.queue = queue;
        this.pipeline = pipeline;
    }

    @GetMapping("/boards")
    public List<BoardView> list() {
        return boards.findEnabled().stream().map(BoardView::of).toList();
    }

    @PostMapping("/boards")
    @Transactional
    public ResponseEntity<BoardView> register(@Valid @RequestBody BoardRegistrationRequest request) {
        UUID companyId = boards.upsertCompany(request.companyName(), request.companySlug(),
                request.emailDomain(), request.careersUrl());
        UUID boardId = boards.registerBoard(companyId, request.source(), request.boardToken(),
                Duration.ofHours(1));

        // Crawl it promptly rather than waiting out a default interval on a board we just added.
        queue.schedule(boardId, Instant.now());

        CompanyBoard board = boards.findById(boardId)
                .orElseThrow(() -> new NotFoundException("Board", boardId));
        return ResponseEntity.status(HttpStatus.CREATED).body(BoardView.of(board));
    }

    /** Forces a crawl now, bypassing the schedule but never the rate limiter. */
    @PostMapping("/boards/{boardId}/crawl")
    public CrawlOutcome crawlNow(@PathVariable UUID boardId) {
        return pipeline.crawl(boardId);
    }

    @PostMapping("/boards/{boardId}/enabled")
    public BoardView setEnabled(@PathVariable UUID boardId, @RequestParam boolean enabled) {
        boards.setEnabled(boardId, enabled);
        if (enabled) {
            queue.schedule(boardId, Instant.now());
        }
        return boards.findById(boardId).map(BoardView::of)
                .orElseThrow(() -> new NotFoundException("Board", boardId));
    }

    @GetMapping("/queue")
    public QueueDepth queueDepth() {
        return new QueueDepth(queue.size());
    }

    public record QueueDepth(long scheduledBoards) {
    }
}
