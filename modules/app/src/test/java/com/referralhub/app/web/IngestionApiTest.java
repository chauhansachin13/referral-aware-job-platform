package com.referralhub.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.referralhub.ingestion.api.IngestionController;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.pipeline.CrawlPipeline;
import com.referralhub.ingestion.schedule.CrawlQueue;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = IngestionController.class)
class IngestionApiTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID BOARD = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BoardStore boards;
    @MockitoBean
    private CrawlQueue queue;
    @MockitoBean
    private CrawlPipeline pipeline;

    private void stubRegistration() {
        when(boards.upsertCompany(anyString(), anyString(), any(), any())).thenReturn(COMPANY);
        when(boards.registerBoard(eq(COMPANY), anyString(), anyString(), any(Duration.class)))
                .thenReturn(BOARD);
        when(boards.findById(BOARD)).thenReturn(Optional.of(new CompanyBoard(
                BOARD, COMPANY, "Acme", "greenhouse", "acme", true, null, null, null,
                Duration.ofHours(1), null, null, 0, 1.0)));
    }

    @Test
    @DisplayName("registering a board returns 201 and queues it immediately")
    void registersAndQueues() throws Exception {
        stubRegistration();

        mvc.perform(post("/api/v1/ingestion/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Acme","companySlug":"acme","source":"greenhouse",
                                 "boardToken":"acme","emailDomain":"acme.com"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BOARD.toString()))
                .andExpect(jsonPath("$.source").value("greenhouse"));

        // A newly registered board should not wait out a default interval before its first crawl.
        verify(queue).schedule(eq(BOARD), any());
    }

    @Test
    @DisplayName("an unsupported ATS is refused before anything is written")
    void rejectsUnsupportedSource() throws Exception {
        mvc.perform(post("/api/v1/ingestion/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Acme","companySlug":"acme","source":"linkedin",
                                 "boardToken":"acme"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.containsString("supported public ATS")));

        verify(boards, never()).upsertCompany(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a slug that is not a slug is refused, with the field named")
    void rejectsBadSlug() throws Exception {
        mvc.perform(post("/api/v1/ingestion/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Acme","companySlug":"Acme Corp!",
                                 "source":"greenhouse","boardToken":"acme"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.containsString("companySlug")));
    }

    @Test
    @DisplayName("missing required fields are all reported at once, not one per round trip")
    void reportsAllValidationFailures() throws Exception {
        mvc.perform(post("/api/v1/ingestion/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("forcing a crawl of a board that does not exist is a 404")
    void unknownBoardIsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        when(pipeline.crawl(missing))
                .thenThrow(new com.referralhub.common.error.NotFoundException("Board", missing));

        mvc.perform(post("/api/v1/ingestion/boards/" + missing + "/crawl"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
