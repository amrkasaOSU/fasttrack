package com.fasttrack.classify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.config.AppProperties;
import com.fasttrack.config.JsonMapper;
import com.fasttrack.llm.LlmInvocation;
import com.fasttrack.llm.LlmInvocationRecorder;
import com.fasttrack.llm.LlmProvider;
import com.fasttrack.llm.OpenAiCompatibleLlmProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every degradation path the product promises, exercised against a stubbed
 * provider: cache hit, valid reply, invalid reply then retry, invalid twice,
 * provider down, and no key at all.
 *
 * <p>The point of these tests is that a user never sees a failure here - a
 * classification always comes back, and {@code method} says how it was made.
 */
class EmailClassificationServiceTest {

    private static final String PATH = "/v1/chat/completions";

    private WireMockServer server;
    private AppProperties props;
    private RecordingRecorder recorder;
    private MapCache cache;

    /** An in-memory cache so the real caching path is exercised without Redis. */
    private static final class MapCache implements ClassificationCache {
        private final Map<String, ClassificationResult> entries = new HashMap<>();
        int reads;
        int writes;

        @Override
        public Optional<ClassificationResult> get(String hash, String version, String model) {
            reads++;
            return Optional.ofNullable(entries.get(hash + version + model));
        }

        @Override
        public void put(String hash, String version, String model, ClassificationResult result) {
            writes++;
            entries.put(hash + version + model, result);
        }

        @Override
        public void evict(String hash, String version, String model) {
            entries.remove(hash + version + model);
        }
    }

    private static final class RecordingRecorder implements LlmInvocationRecorder {
        final List<LlmInvocation> recorded = new ArrayList<>();

        @Override
        public void record(LlmInvocation invocation) {
            recorded.add(invocation);
        }
    }

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();

        props = new AppProperties();
        props.getLlm().setBaseUrl("http://localhost:" + server.port() + "/v1");
        props.getLlm().setApiKey("test-key");
        props.getLlm().setModel("test-model");
        props.getLlm().setMaxRetries(0); // transport retries off; this tests the classifier's own retry
        props.getLlm().setTimeoutSeconds(5);

        recorder = new RecordingRecorder();
        cache = new MapCache();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private EmailClassificationService service() {
        return service(new OpenAiCompatibleLlmProvider(props, JsonMapper.build()));
    }

    private EmailClassificationService service(LlmProvider provider) {
        return new EmailClassificationService(
                provider,
                new PromptLibrary(),
                new ClassificationJsonParser(JsonMapper.build()),
                new RuleBasedClassifier(),
                cache,
                recorder);
    }

    private static String reply(String content) {
        return """
                {"model":"test-model","choices":[{"message":{"role":"assistant","content":%s}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":30}}
                """.formatted(JsonMapper.build().valueToTree(content).toString());
    }

    private EmailContent email() {
        return new EmailContent(
                "no-reply@greenhouse-mail.io",
                "Stripe Recruiting",
                "Your application to Stripe",
                "Thank you for your interest. We have decided to move forward with other candidates.",
                Instant.parse("2027-02-16T12:00:00Z"));
    }

    @Test
    @DisplayName("a valid LLM reply is used, recorded and cached")
    void happyPath() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("{\"category\":\"REJECTION\",\"confidence\":0.95,"
                        + "\"company\":\"Stripe\",\"one_sentence_reason\":\"Explicit rejection.\"}"))));

        var result = service().classify(email(), null);

        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        assertThat(result.method()).isEqualTo(ClassificationMethod.LLM);
        assertThat(result.company()).isEqualTo("Stripe");
        assertThat(result.cacheHit()).isFalse();
        assertThat(cache.writes).isEqualTo(1);
        assertThat(recorder.recorded).singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.isSuccess()).isTrue();
                    assertThat(invocation.isFellBack()).isFalse();
                    assertThat(invocation.isRetried()).isFalse();
                    assertThat(invocation.getPromptTokens()).isEqualTo(100);
                });
    }

    @Test
    @DisplayName("the second time the same email is seen, no call is made at all")
    void cacheHitAvoidsTheCall() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("{\"category\":\"REJECTION\",\"confidence\":0.95}"))));

        var service = service();
        service.classify(email(), null);
        var second = service.classify(email(), null);

        assertThat(second.cacheHit()).isTrue();
        assertThat(second.category()).isEqualTo(EmailCategory.REJECTION);
        server.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(recorder.recorded).hasSize(1);
    }

    @Test
    @DisplayName("an invalid reply is retried once, and the retry's answer is used")
    void retriesInvalidJsonOnce() {
        server.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200).withBody(reply("I think it's a rejection.")))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(PATH))
                .inScenario("retry").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200)
                        .withBody(reply("{\"category\":\"REJECTION\",\"confidence\":0.9}"))));

        var result = service().classify(email(), null);

        assertThat(result.method()).isEqualTo(ClassificationMethod.LLM);
        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(recorder.recorded).singleElement()
                .satisfies(invocation -> assertThat(invocation.isRetried()).isTrue());
    }

    @Test
    @DisplayName("two invalid replies fall back to the rule-based classifier, not to an error")
    void fallsBackAfterTwoInvalidReplies() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("still not JSON"))));

        var result = service().classify(email(), null);

        assertThat(result.method()).isEqualTo(ClassificationMethod.RULE);
        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        assertThat(cache.writes).isZero();
        assertThat(recorder.recorded).singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.isSuccess()).isFalse();
                    assertThat(invocation.isFellBack()).isTrue();
                });
    }

    @Test
    @DisplayName("an unreachable provider falls back to rules")
    void fallsBackWhenProviderIsDown() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        var result = service().classify(email(), null);

        assertThat(result.method()).isEqualTo(ClassificationMethod.RULE);
        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        assertThat(recorder.recorded).singleElement()
                .satisfies(invocation -> assertThat(invocation.isFellBack()).isTrue());
    }

    @Test
    @DisplayName("with no API key the classifier works and never calls out")
    void worksWithNoKey() {
        props.getLlm().setApiKey("");

        var result = service().classify(email(), null);

        assertThat(result.method()).isEqualTo(ClassificationMethod.RULE);
        assertThat(result.category()).isEqualTo(EmailCategory.REJECTION);
        server.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(recorder.recorded).isEmpty();
    }

    @Test
    @DisplayName("a model that invents a category falls back rather than inventing a status change")
    void unknownCategoryFallsBack() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("{\"category\":\"PROBABLY_GOOD\",\"confidence\":0.99}"))));

        var result = service().classify(email(), null);

        assertThat(result.method()).isEqualTo(ClassificationMethod.RULE);
    }

    @Test
    @DisplayName("caching can be switched off, which is what the evaluation harness does")
    void cacheCanBeBypassed() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("{\"category\":\"REJECTION\",\"confidence\":0.9}"))));

        var service = service();
        service.classify(email(), null, false);
        service.classify(email(), null, false);

        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(cache.reads).isZero();
        assertThat(cache.writes).isZero();
    }

    @Test
    @DisplayName("a low-confidence answer is returned but marked for review")
    void lowConfidenceIsFlagged() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(reply("{\"category\":\"INTERVIEW_REQUEST\",\"confidence\":0.42}"))));

        var result = service().classify(email(), null);

        assertThat(result.needsReview()).isTrue();
        assertThat(result.category()).isEqualTo(EmailCategory.INTERVIEW_REQUEST);
    }
}
