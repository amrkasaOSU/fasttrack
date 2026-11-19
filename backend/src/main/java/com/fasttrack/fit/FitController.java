package com.fasttrack.fit;

import com.fasttrack.auth.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The fit checker. */
@RestController
@RequestMapping("/api/fit")
@Tag(name = "Fit", description = "Score your resume against a job posting")
public class FitController {

    private final FitService fit;
    private final AuthorizationService authorization;

    public FitController(FitService fit, AuthorizationService authorization) {
        this.fit = fit;
        this.authorization = authorization;
    }

    /**
     * Either {@code postingText} (preferred) or {@code url}. A URL is fetched
     * server-side and falls back to asking the user to paste when the page
     * needs JavaScript.
     */
    public record FitRequest(
            @Size(max = 40_000) String postingText,
            @Size(max = 2_000) String url,
            UUID applicationId) {}

    public record ScoringExplanation(String weights, int hardMismatchCap, List<BandLegend> bands) {

        public record BandLegend(String band, String label, int minimumScore) {}
    }

    @PostMapping
    @Operation(summary = "Score your active resume against a posting")
    public FitService.FitCheckResult check(@Valid @RequestBody FitRequest request) {
        UUID userId = authorization.requireUserId();

        if (request.postingText() != null && !request.postingText().isBlank()) {
            return fit.check(userId, request.postingText(), null, request.applicationId());
        }
        if (request.url() != null && !request.url().isBlank()) {
            return fit.checkUrl(userId, request.url(), request.applicationId());
        }
        throw new com.fasttrack.common.BadRequestException(
                "Paste the posting text, or give a link to it.");
    }

    @GetMapping
    @Operation(summary = "Your recent fit checks")
    public List<FitService.FitCheckResult> recent() {
        return fit.recent(authorization.requireUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One fit check with its full breakdown")
    public FitService.FitCheckResult get(@PathVariable UUID id) {
        return fit.get(authorization.requireUserId(), id);
    }

    @PostMapping("/{id}/link/{applicationId}")
    @Operation(summary = "Attach a fit check to an application")
    public FitService.FitCheckResult link(@PathVariable UUID id, @PathVariable UUID applicationId) {
        return fit.linkToApplication(authorization.requireUserId(), id, applicationId);
    }

    @GetMapping("/explanation")
    @Operation(summary = "How the score is calculated, for the UI legend")
    public ScoringExplanation explanation() {
        return new ScoringExplanation(
                FitScoreCalculator.weightsDescription(),
                FitScoreCalculator.HARD_MISMATCH_CAP,
                List.of(
                        new ScoringExplanation.BandLegend("STRONG", FitBand.STRONG.label(), FitBand.STRONG.minimumScore()),
                        new ScoringExplanation.BandLegend("GOOD", FitBand.GOOD.label(), FitBand.GOOD.minimumScore()),
                        new ScoringExplanation.BandLegend("STRETCH", FitBand.STRETCH.label(), FitBand.STRETCH.minimumScore()),
                        new ScoringExplanation.BandLegend("LONG_SHOT", FitBand.LONG_SHOT.label(), FitBand.LONG_SHOT.minimumScore())));
    }
}
