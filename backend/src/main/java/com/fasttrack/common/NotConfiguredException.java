package com.fasttrack.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a feature needs credentials the deployment does not have
 * (Gmail OAuth, LLM key). The frontend renders this as a "not configured"
 * state rather than an error - see the graceful-degradation requirement.
 */
public class NotConfiguredException extends ApiException {

    private final String feature;

    public NotConfiguredException(String feature, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "not_configured", message);
        this.feature = feature;
    }

    public String getFeature() { return feature; }
}
