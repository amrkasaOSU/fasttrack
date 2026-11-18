package com.fasttrack.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.config.AppProperties;
import com.fasttrack.config.JsonMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP client, against a stubbed OpenAI-compatible endpoint.
 *
 * <p>What is asserted here is what a provider swap depends on: the wire shape,
 * the auth header, and which failures are worth retrying.
 */
class OpenAiCompatibleLlmProviderTest {

    private static final String PATH = "/v1/chat/completions";

    private WireMockServer server;
    private AppProperties props;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();

        props = new AppProperties();
        props.getLlm().setBaseUrl("http://localhost:" + server.port() + "/v1");
        props.getLlm().setApiKey("test-key");
        props.getLlm().setModel("test-model");
        props.getLlm().setMaxRetries(1);
        props.getLlm().setTimeoutSeconds(5);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private LlmProvider provider() {
        return new OpenAiCompatibleLlmProvider(props, JsonMapper.build());
    }

    private static String completion(String content) {
        return """
                {"id":"x","model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                 "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":40}}
                """.formatted(content);
    }

    @Test
    @DisplayName("sends the OpenAI chat-completions shape with the key in the Authorization header")
    void sendsExpectedRequest() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(completion("hello"))));

        var response = provider().complete(
                LlmRequest.json("system text", "user text", LlmPurpose.CLASSIFY_EMAIL));

        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.promptTokens()).isEqualTo(120);
        assertThat(response.completionTokens()).isEqualTo(40);
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);

        server.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.0")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", equalTo("user text"))));
    }

    @Test
    @DisplayName("a rate-limited call is retried and can still succeed")
    void retriesOnRateLimit() {
        server.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(completion("ok"))));

        var response = provider().complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL));

        assertThat(response.content()).isEqualTo("ok");
        assertThat(response.attempts()).isEqualTo(2);
        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a server error is retried, then reported as retryable when it keeps failing")
    void retriesServerErrorsThenGivesUp() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider().complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)))
                .isInstanceOf(LlmUnavailableException.class)
                .satisfies(ex -> assertThat(((LlmUnavailableException) ex).isRetryable()).isTrue());

        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a bad request or bad key is not retried - retrying cannot help")
    void doesNotRetryClientErrors() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> provider().complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)))
                .isInstanceOf(LlmUnavailableException.class)
                .satisfies(ex -> assertThat(((LlmUnavailableException) ex).isRetryable()).isFalse());

        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a response with no choices is reported rather than returned as empty text")
    void emptyChoices() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody("{\"choices\":[]}")));

        assertThatThrownBy(() -> provider().complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("no choices");
    }

    @Test
    @DisplayName("a non-JSON response body is reported as unavailable, not parsed")
    void malformedBody() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody("<html>gateway</html>")));

        assertThatThrownBy(() -> provider().complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    @DisplayName("with no API key the provider reports itself unconfigured and never calls out")
    void unconfigured() {
        props.getLlm().setApiKey("");
        LlmProvider provider = provider();

        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.complete(
                LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("No LLM API key");

        server.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("a base URL with a trailing slash still resolves to one endpoint")
    void trailingSlashBaseUrl() {
        props.getLlm().setBaseUrl("http://localhost:" + server.port() + "/v1/");
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody(completion("ok"))));

        assertThat(provider().complete(LlmRequest.json("s", "u", LlmPurpose.CLASSIFY_EMAIL)).content())
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("the configured model name is what callers see")
    void reportsModel() {
        assertThat(provider().model()).isEqualTo("test-model");
        assertThat(provider().isConfigured()).isTrue();
    }
}
