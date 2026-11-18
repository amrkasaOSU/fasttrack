package com.fasttrack.classify;

import java.util.Optional;

/**
 * A cache that remembers nothing. Used by the offline evaluation, where every
 * email must actually be classified, and by tests that assert call counts.
 */
public class NoOpClassificationCache implements ClassificationCache {

    @Override
    public Optional<ClassificationResult> get(String contentHash, String promptVersion, String model) {
        return Optional.empty();
    }

    @Override
    public void put(String contentHash, String promptVersion, String model, ClassificationResult result) {
        // deliberately nothing
    }

    @Override
    public void evict(String contentHash, String promptVersion, String model) {
        // deliberately nothing
    }
}
