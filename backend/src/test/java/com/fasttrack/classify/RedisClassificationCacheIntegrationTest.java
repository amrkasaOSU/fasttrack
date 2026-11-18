package com.fasttrack.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The cache against a real Redis, including what happens to keys and dates. */
class RedisClassificationCacheIntegrationTest extends AbstractIntegrationTest {

    @Autowired ClassificationCache cache;
    @Autowired StringRedisTemplate redis;

    private ClassificationResult sample() {
        return new ClassificationResult(
                EmailCategory.INTERVIEW_SCHEDULED, "Datadog", "SWE Intern", "JR1",
                Instant.parse("2027-03-04T20:00:00Z"), null, null,
                0.91, "Confirms a date and time", ClassificationMethod.LLM,
                "test-model", "v1", false, 120);
    }

    @Test
    @DisplayName("a stored result comes back with every field intact, timestamps included")
    void roundTrip() {
        String hash = UUID.randomUUID().toString();
        cache.put(hash, "v1", "test-model", sample());

        var found = cache.get(hash, "v1", "test-model");

        assertThat(found).isPresent();
        assertThat(found.get().category()).isEqualTo(EmailCategory.INTERVIEW_SCHEDULED);
        assertThat(found.get().company()).isEqualTo("Datadog");
        assertThat(found.get().interviewDatetime()).isEqualTo(Instant.parse("2027-03-04T20:00:00Z"));
        assertThat(found.get().confidence()).isEqualTo(0.91);
        assertThat(found.get().method()).isEqualTo(ClassificationMethod.LLM);
    }

    @Test
    @DisplayName("an unknown email is a miss, not an error")
    void missReturnsEmpty() {
        assertThat(cache.get(UUID.randomUUID().toString(), "v1", "test-model")).isEmpty();
    }

    @Test
    @DisplayName("a different prompt version or model never reads another one's answer")
    void keyIncludesPromptVersionAndModel() {
        String hash = UUID.randomUUID().toString();
        cache.put(hash, "v1", "model-a", sample());

        assertThat(cache.get(hash, "v2", "model-a")).isEmpty();
        assertThat(cache.get(hash, "v1", "model-b")).isEmpty();
        assertThat(cache.get(hash, "v1", "model-a")).isPresent();
    }

    @Test
    @DisplayName("entries expire, so a changed classifier is not pinned forever")
    void entriesCarryATtl() {
        String hash = UUID.randomUUID().toString();
        cache.put(hash, "v1", "test-model", sample());

        Long ttl = redis.getExpire("fasttrack:classify:v1:test-model:" + hash);

        assertThat(ttl).isNotNull().isGreaterThan(0);
    }

    @Test
    @DisplayName("eviction removes the entry")
    void eviction() {
        String hash = UUID.randomUUID().toString();
        cache.put(hash, "v1", "test-model", sample());

        cache.evict(hash, "v1", "test-model");

        assertThat(cache.get(hash, "v1", "test-model")).isEmpty();
    }

    @Test
    @DisplayName("an unreadable entry is treated as a miss rather than breaking classification")
    void corruptEntryIsAMiss() {
        String hash = UUID.randomUUID().toString();
        redis.opsForValue().set("fasttrack:classify:v1:test-model:" + hash, "{not json");

        assertThat(cache.get(hash, "v1", "test-model")).isEmpty();
    }

    @Test
    @DisplayName("the same email content always produces the same cache key")
    void contentHashIsStable() {
        var first = new EmailContent("a@b.com", "A", "Subject", "Body", Instant.now());
        var second = new EmailContent("a@b.com", "A", "Subject", "Body", Instant.now().plusSeconds(60));
        var different = new EmailContent("a@b.com", "A", "Subject", "Body text", Instant.now());

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.contentHash()).isNotEqualTo(different.contentHash());
        assertThat(first.contentHash()).hasSize(64);
    }

    @Test
    @DisplayName("a long body is truncated before it is ever sent to a model")
    void bodyTruncation() {
        String huge = "x".repeat(EmailContent.MAX_BODY_CHARS + 500);
        var content = new EmailContent("a@b.com", "A", "Subject", huge, Instant.now());

        assertThat(content.truncatedBody()).hasSizeLessThan(huge.length());
        assertThat(content.truncatedBody()).endsWith("[truncated]");
        assertThat(new EmailContent("a@b.com", "A", "S", null, Instant.now()).truncatedBody()).isEmpty();
    }
}
