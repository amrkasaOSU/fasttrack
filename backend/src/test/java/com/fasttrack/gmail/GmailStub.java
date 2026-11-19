package com.fasttrack.gmail;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A stand-in for Gmail and Google's token endpoint, so the whole sync can be
 * exercised end to end without a Google account.
 *
 * <p>It speaks the real wire format the official client expects, which is what
 * makes the test meaningful: the production code path, the production client,
 * and the production parsing all run.
 */
public final class GmailStub {

    private final WireMockServer server;

    public GmailStub() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        stubTokenEndpoint();
    }

    public WireMockServer server() {
        return server;
    }

    public int port() {
        return server.port();
    }

    public String rootUrl() {
        return "http://localhost:" + server.port() + "/";
    }

    public String tokenUri() {
        return "http://localhost:" + server.port() + "/token";
    }

    public void stop() {
        server.stop();
    }

    public void reset() {
        server.resetAll();
        stubTokenEndpoint();
    }

    private void stubTokenEndpoint() {
        server.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"access_token":"stub-access-token","expires_in":3599,"token_type":"Bearer"}
                        """)));
    }

    public void stubProfile(String emailAddress, String historyId) {
        server.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/profile")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"emailAddress":"%s","messagesTotal":1200,"threadsTotal":900,"historyId":"%s"}
                        """.formatted(emailAddress, historyId))));
    }

    /** One page of message ids, with no next page. */
    public void stubMessageList(List<String> ids) {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                messages.append(',');
            }
            messages.append("{\"id\":\"").append(ids.get(i)).append("\",\"threadId\":\"t-")
                    .append(ids.get(i)).append("\"}");
        }
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messages\":[" + messages + "],\"resultSizeEstimate\":" + ids.size() + "}")));
    }

    /** History since a cursor: the ids Gmail says were added. */
    public void stubHistory(List<String> addedIds, String newHistoryId) {
        StringBuilder added = new StringBuilder();
        for (int i = 0; i < addedIds.size(); i++) {
            if (i > 0) {
                added.append(',');
            }
            added.append("{\"message\":{\"id\":\"").append(addedIds.get(i))
                 .append("\",\"threadId\":\"t-").append(addedIds.get(i)).append("\"}}");
        }
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/history"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"history":[{"id":"%s","messagesAdded":[%s]}],"historyId":"%s"}
                                """.formatted(newHistoryId, added, newHistoryId))));
    }

    /** A realistic full message, body base64url-encoded as Gmail sends it. */
    public void stubMessage(String id, String from, String subject, String body, long internalDateMillis) {
        String encoded = Base64.getUrlEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String snippet = body.length() > 100 ? body.substring(0, 100) : body;

        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/messages/" + id))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "%s",
                                  "threadId": "t-%s",
                                  "snippet": %s,
                                  "internalDate": "%d",
                                  "payload": {
                                    "mimeType": "multipart/alternative",
                                    "headers": [
                                      {"name":"From","value":%s},
                                      {"name":"Subject","value":%s},
                                      {"name":"Date","value":"Mon, 16 Feb 2027 12:00:00 -0500"}
                                    ],
                                    "parts": [
                                      {"mimeType":"text/plain","body":{"size":%d,"data":"%s"}}
                                    ]
                                  }
                                }
                                """.formatted(id, id, json(snippet), internalDateMillis,
                                json(from), json(subject), body.length(), encoded))));
    }

    /** A message that fails twice then succeeds, to exercise retry with backoff. */
    public void stubFlakyMessage(String id, String from, String subject, String body, long internalDateMillis) {
        String scenario = "flaky-" + id;
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/messages/" + id))
                .inScenario(scenario).whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));

        String encoded = Base64.getUrlEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/messages/" + id))
                .inScenario(scenario).whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"%s","threadId":"t-%s","snippet":"retry","internalDate":"%d",
                                 "payload":{"mimeType":"text/plain",
                                 "headers":[{"name":"From","value":%s},{"name":"Subject","value":%s}],
                                 "body":{"size":%d,"data":"%s"}}}
                                """.formatted(id, id, internalDateMillis, json(from), json(subject),
                                body.length(), encoded))));
    }

    public void stubMessageFailure(String id, int status) {
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock
                .urlPathEqualTo("/gmail/v1/users/me/messages/" + id))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":" + status + ",\"message\":\"stubbed failure\"}}")));
    }

    /** Google's response when a grant has been revoked. */
    public void stubTokenRejected() {
        server.stubFor(post(urlEqualTo("/token")).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")));
    }

    public int requestCountFor(String urlPath) {
        return server.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(urlPath))).size();
    }

    public List<String> fetchedMessageIds() {
        List<String> ids = new ArrayList<>();
        server.getAllServeEvents().forEach(event -> {
            String url = event.getRequest().getUrl();
            if (url.contains("/messages/")) {
                String tail = url.substring(url.lastIndexOf("/messages/") + "/messages/".length());
                ids.add(tail.contains("?") ? tail.substring(0, tail.indexOf('?')) : tail);
            }
        });
        return ids;
    }

    private static String json(String value) {
        return com.fasttrack.config.JsonMapper.shared().valueToTree(value).toString();
    }
}
