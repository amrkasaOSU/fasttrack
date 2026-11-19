package com.fasttrack.review;

import com.fasttrack.auth.AuthorizationService;
import com.fasttrack.classify.EmailCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Needs review queue and the corrections made from it. */
@RestController
@RequestMapping("/api/review")
@Tag(name = "Review", description = "Low-confidence classifications and user corrections")
public class ReviewController {

    private final ReviewService review;
    private final AuthorizationService authorization;

    public ReviewController(ReviewService review, AuthorizationService authorization) {
        this.review = review;
        this.authorization = authorization;
    }

    public record CorrectionRequest(
            @NotNull EmailCategory category,
            @Size(max = 200) String company,
            @Size(max = 200) String positionTitle) {}

    @GetMapping
    @Operation(summary = "Everything the system was not confident about")
    public ReviewService.ReviewQueue queue() {
        return review.queue(authorization.requireUserId());
    }

    @PostMapping("/emails/{emailId}/correct")
    @Operation(summary = "Correct one email's classification and re-derive its application")
    public ReviewService.ReviewEmail correct(@PathVariable UUID emailId,
                                             @Valid @RequestBody CorrectionRequest request) {
        return review.correct(authorization.requireUserId(), emailId,
                request.category(), request.company(), request.positionTitle());
    }

    @GetMapping("/accuracy")
    @Operation(summary = "Accuracy measured from your own corrections, with its caveat")
    public ReviewService.LiveAccuracy accuracy() {
        return review.liveAccuracy(authorization.requireUserId());
    }
}
