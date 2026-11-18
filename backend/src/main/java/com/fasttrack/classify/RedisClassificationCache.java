package com.fasttrack.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.config.AppProperties;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The Redis-backed {@link ClassificationCache}, keyed by the email's content hash
 * together with the prompt version and model.
 *
 * <p>Two reasons the key includes prompt version and model: re-running the
 * evaluation after a prompt change must not read stale answers, and switching
 * model must not silently inherit another model's judgements.
 *
 * <p>Redis being unavailable is never fatal here. A failed read is a miss and
 * a failed write is ignored - the classifier still works, it just costs an
 * extra call.
 */
@Component
public class RedisClassificationCache implements ClassificationCache {

    private static final Logger log = LoggerFactory.getLogger(ClassificationCache.class);
    private static final String KEY_PREFIX = "fasttrack:classify:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisClassificationCache(StringRedisTemplate redis, ObjectMapper llmObjectMapper, AppProperties props) {
        this.redis = redis;
        this.mapper = llmObjectMapper;
        this.ttl = Duration.ofDays(Math.max(1, props.getLlm().getCacheTtlDays()));
    }

    @Override
    public Optional<ClassificationResult> get(String contentHash, String promptVersion, String model) {
        try {
            String cached = redis.opsForValue().get(key(contentHash, promptVersion, model));
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(cached, ClassificationResult.class));
        } catch (DataAccessException ex) {
            log.warn("Classification cache unavailable, treating as a miss: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        } catch (Exception ex) {
            // A stale or unreadable entry is a miss, not an error.
            log.warn("Discarding unreadable cache entry: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String contentHash, String promptVersion, String model, ClassificationResult result) {
        try {
            redis.opsForValue().set(
                    key(contentHash, promptVersion, model),
                    mapper.writeValueAsString(result),
                    ttl);
        } catch (DataAccessException | com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Could not cache classification: {}", ex.getClass().getSimpleName());
        }
    }

    /** Used by tests and by "re-run classification" flows. */
    @Override
    public void evict(String contentHash, String promptVersion, String model) {
        try {
            redis.delete(key(contentHash, promptVersion, model));
        } catch (DataAccessException ignored) {
            // nothing to do
        }
    }

    private String key(String contentHash, String promptVersion, String model) {
        return KEY_PREFIX + promptVersion + ":" + (model == null ? "none" : model) + ":" + contentHash;
    }
}
