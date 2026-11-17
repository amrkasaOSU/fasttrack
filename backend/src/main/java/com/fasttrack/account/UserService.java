package com.fasttrack.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creation and lookup of accounts. The only place users are written. */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /**
     * Finds the account for a Google identity, creating it on first sign-in and
     * refreshing the profile fields on later sign-ins.
     */
    @Transactional
    public User upsertGoogleUser(String googleSubject, String email, String displayName, String pictureUrl) {
        User user = users.findByGoogleSubject(googleSubject)
                .or(() -> users.findByEmailIgnoreCase(email))
                .orElseGet(() -> User.googleUser(email, displayName, pictureUrl, googleSubject));

        user.setGoogleSubject(googleSubject);
        user.setEmail(email);
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName);
        }
        if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
            user.setDisplayName(email);
        }
        user.setPictureUrl(pictureUrl);
        user.setLastLoginAt(Instant.now());
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    @Transactional(readOnly = true)
    public User require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new IllegalStateException("Session references a user that no longer exists"));
    }

    @Transactional
    public void recordLogin(UUID id) {
        users.findById(id).ifPresent(u -> u.setLastLoginAt(Instant.now()));
    }
}
