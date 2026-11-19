package com.fasttrack.gmail;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells Google to forget a refresh token when the user disconnects or deletes
 * their data.
 *
 * <p>Deleting our copy is necessary but not sufficient: until the grant is
 * revoked at Google, the token would still work if it leaked. The call is
 * best-effort - if Google is unreachable, the local deletion still happens,
 * because the user asked for their data to be gone.
 */
@Component
public class GoogleTokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenRevoker.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String revokeUri;

    public GoogleTokenRevoker(@Value("${app.gmail.revoke-uri:https://oauth2.googleapis.com/revoke}") String revokeUri) {
        this.revokeUri = revokeUri;
    }

    /** @return true when Google confirmed the revocation */
    public boolean revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(revokeUri))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)))
                    .build();

            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            boolean revoked = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!revoked) {
                log.warn("Google declined the token revocation (HTTP {}); deleting locally anyway",
                        response.statusCode());
            }
            return revoked;
        } catch (Exception ex) {
            log.warn("Could not reach Google to revoke the token ({}); deleting locally anyway",
                    ex.getClass().getSimpleName());
            return false;
        }
    }
}
