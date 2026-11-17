package com.fasttrack.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can actually do.
 *
 * <p>The frontend calls this before rendering so that features without
 * credentials show an explicit "not configured" state instead of failing when
 * used. No secret values are exposed - only booleans and the model name.
 */
@RestController
@RequestMapping("/api/config")
@Tag(name = "Config", description = "Feature availability for this deployment")
public class ConfigController {

    private final AppProperties props;

    public ConfigController(AppProperties props) {
        this.props = props;
    }

    public record FeatureConfig(
            boolean gmailConfigured,
            boolean llmConfigured,
            boolean tokenEncryptionConfigured,
            boolean demoEnabled,
            String llmModel,
            int syncInitialDays) {}

    @GetMapping
    @Operation(summary = "Which optional integrations are configured")
    public FeatureConfig get() {
        return new FeatureConfig(
                props.getGoogle().isConfigured() && props.isTokenEncryptionConfigured(),
                props.getLlm().isConfigured(),
                props.isTokenEncryptionConfigured(),
                props.isDemoEnabled(),
                props.getLlm().isConfigured() ? props.getLlm().getModel() : null,
                props.getSync().getInitialDays());
    }
}
