package com.fasttrack.classify;

import java.util.Optional;

/**
 * Where classification results are remembered so the same email is never paid
 * for twice.
 *
 * <p>An interface rather than a class so the offline evaluation and unit tests
 * can run the real classification path with caching switched off - see
 * {@link NoOpClassificationCache}.
 */
public interface ClassificationCache {

    Optional<ClassificationResult> get(String contentHash, String promptVersion, String model);

    void put(String contentHash, String promptVersion, String model, ClassificationResult result);

    void evict(String contentHash, String promptVersion, String model);
}
