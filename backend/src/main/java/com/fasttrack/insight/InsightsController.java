package com.fasttrack.insight;

import com.fasttrack.auth.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything the dashboard draws. */
@RestController
@RequestMapping("/api/insights")
@Tag(name = "Insights", description = "Dashboard totals, charts and action items")
public class InsightsController {

    private final InsightsService insights;
    private final AuthorizationService authorization;

    public InsightsController(InsightsService insights, AuthorizationService authorization) {
        this.insights = insights;
        this.authorization = authorization;
    }

    @GetMapping
    @Operation(summary = "Totals, weekly volume, funnel, response rates, timings and action items")
    public InsightsService.Insights get() {
        return insights.forUser(authorization.requireUserId());
    }
}
