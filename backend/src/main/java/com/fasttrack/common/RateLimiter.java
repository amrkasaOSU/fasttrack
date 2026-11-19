package com.fasttrack.common;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-user rate limiting in Redis: a fixed window counter per action.
 *
 * <p>Fixed windows, not a token bucket, because the limits here protect a free
 * API tier and a single small server - the extra smoothness of a bucket is not
 * worth the extra moving parts, and a burst at a window boundary costs nothing
 * at this scale.
 *
 * <p>If Redis is unavailable the call is allowed. Losing rate limiting for a
 * moment is better than taking the product down.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String PREFIX = "fasttrack:ratelimit:";

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Consumes one unit of the user's allowance for {@code action}.
     *
     * @throws RateLimitedException when the allowance for this window is used up
     */
    public void checkAndConsume(UUID userId, String action, int limitPerHour) {
        if (limitPerHour <= 0) {
            return;
        }
        String key = PREFIX + action + ":" + userId + ":" + (System.currentTimeMillis() / 3_600_000);
        try {
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, Duration.ofHours(1));
            }
            if (used != null && used > limitPerHour) {
                long secondsLeft = 3600 - (System.currentTimeMillis() / 1000) % 3600;
                throw new RateLimitedException(
                        "You have reached the limit for this action. Try again in "
                        + Math.max(1, secondsLeft / 60) + " minutes.", secondsLeft);
            }
        } catch (DataAccessException ex) {
            log.warn("Rate limiter unavailable, allowing the request: {}", ex.getClass().getSimpleName());
        }
    }

    /** How many units of the allowance are left, for display. */
    public int remaining(UUID userId, String action, int limitPerHour) {
        String key = PREFIX + action + ":" + userId + ":" + (System.currentTimeMillis() / 3_600_000);
        try {
            String used = redis.opsForValue().get(key);
            int consumed = used == null ? 0 : Integer.parseInt(used);
            return Math.max(0, limitPerHour - consumed);
        } catch (DataAccessException | NumberFormatException ex) {
            return limitPerHour;
        }
    }
}
