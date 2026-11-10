package com.springtime.bigquery.retail.event.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.event.dto.DeadLetterView;
import com.springtime.bigquery.retail.event.dto.ReplayResult;
import com.springtime.bigquery.retail.event.application.DeadLetterReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retail Events", description = "Retail event intake and dead-letter replay")
@RestController
@RequestMapping(value = "/api/v1/retail/events/dead-letters", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeadLetterController {

    private final DeadLetterReplayService replayService;

    public DeadLetterController(DeadLetterReplayService replayService) {
        this.replayService = replayService;
    }

    @Operation(operationId = "listDeadLetters",
            summary = "List durable dead-letter records, one page at a time")
    @GetMapping
    public PageResponse<DeadLetterView> list(
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return replayService.list(pageToken, limit);
    }

    @Operation(operationId = "replayDeadLetter", summary = "Republish one dead-letter event")
    @PostMapping("/{eventId}/replay")
    public ReplayResult replay(
            @Parameter(description = "Original event id") @PathVariable String eventId) {
        return replayService.replay(eventId);
    }
}
