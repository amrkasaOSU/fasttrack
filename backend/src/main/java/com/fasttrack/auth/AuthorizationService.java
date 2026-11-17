package com.fasttrack.auth;

import com.fasttrack.common.ForbiddenException;
import com.fasttrack.common.NotFoundException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The single place ownership is decided.
 *
 * <p>Rules this class enforces:
 * <ul>
 *   <li>The acting user id comes from the session, never from the request.
 *       No endpoint accepts a client-supplied user id.</li>
 *   <li>A resource owned by someone else is reported as {@code 404}, not
 *       {@code 403}, so ids cannot be probed for existence.</li>
 *   <li>The demo account may read everything but cannot perform destructive
 *       account-level operations.</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    /** The authenticated principal, or empty if the request is anonymous. */
    public AppUserPrincipal currentPrincipalOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AuthenticatedUser user && auth.isAuthenticated()) {
            return user.getPrincipal();
        }
        return null;
    }

    public AppUserPrincipal requirePrincipal() {
        AppUserPrincipal principal = currentPrincipalOrNull();
        if (principal == null) {
            throw new ForbiddenException("You must be signed in.");
        }
        return principal;
    }

    public UUID requireUserId() {
        return requirePrincipal().userId();
    }

    /**
     * Asserts that {@code ownerId} is the signed-in user.
     *
     * @param resourceName used in the 404 message, e.g. {@code "Application"}
     */
    public void requireOwner(UUID ownerId, String resourceName) {
        UUID current = requireUserId();
        if (ownerId == null || !ownerId.equals(current)) {
            throw new NotFoundException(resourceName);
        }
    }

    /** Blocks account-level destructive actions for the shared demo account. */
    public void requireNotDemo(String action) {
        if (requirePrincipal().demo()) {
            throw new ForbiddenException("The demo account cannot " + action + ".");
        }
    }
}
