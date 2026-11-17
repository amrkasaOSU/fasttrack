package com.fasttrack.auth;

import com.fasttrack.account.User;
import java.io.Serializable;
import java.util.UUID;

/**
 * What the session actually holds: identity, never credentials.
 *
 * <p>Sessions are serialized into Redis, so this type stays small, immutable
 * and {@link Serializable}. Tokens are never part of it - the Gmail refresh
 * token lives encrypted in the database and is read only by the sync job.
 */
public record AppUserPrincipal(
        UUID userId,
        String email,
        String displayName,
        String pictureUrl,
        boolean demo,
        boolean readOnly) implements Serializable {

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPictureUrl(),
                user.isDemo(),
                user.isReadOnly());
    }
}
