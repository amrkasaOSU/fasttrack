package com.fasttrack.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches only when both Google OAuth values are present and non-blank.
 *
 * <p>{@code @ConditionalOnProperty} is not enough here: the properties are
 * always *defined* (as empty strings) so the application can start and report
 * "Gmail not configured" instead of failing.
 */
public class GoogleConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String id = context.getEnvironment().getProperty("app.google.client-id");
        String secret = context.getEnvironment().getProperty("app.google.client-secret");
        return StringUtils.hasText(id) && StringUtils.hasText(secret);
    }
}
