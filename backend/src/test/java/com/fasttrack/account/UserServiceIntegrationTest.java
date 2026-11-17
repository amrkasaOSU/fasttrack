package com.fasttrack.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Account creation on first Google sign-in, and what happens on later ones. */
class UserServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    UserRepository users;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("the first sign-in creates the account")
    void firstSignInCreatesAccount() {
        String email = uniqueEmail();
        User created = userService.upsertGoogleUser("sub-" + email, email, "Amr K", "https://pic/1.png");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getEmail()).isEqualTo(email);
        assertThat(created.getDisplayName()).isEqualTo("Amr K");
        assertThat(created.isDemo()).isFalse();
        assertThat(created.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("a later sign-in updates the profile instead of creating a second account")
    void secondSignInUpdatesInPlace() {
        String email = uniqueEmail();
        String subject = "sub-" + email;
        UUID firstId = userService.upsertGoogleUser(subject, email, "Old Name", null).getId();

        User updated = userService.upsertGoogleUser(subject, email, "New Name", "https://pic/2.png");

        assertThat(updated.getId()).isEqualTo(firstId);
        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getPictureUrl()).isEqualTo("https://pic/2.png");
        assertThat(users.findAll().stream().filter(u -> email.equalsIgnoreCase(u.getEmail())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an existing account found by email is linked to the Google subject")
    void matchesExistingAccountByEmail() {
        String email = uniqueEmail();
        User seeded = users.save(User.googleUser(email, "Seeded", null, null));

        User linked = userService.upsertGoogleUser("sub-new", email, "Seeded", null);

        assertThat(linked.getId()).isEqualTo(seeded.getId());
        assertThat(linked.getGoogleSubject()).isEqualTo("sub-new");
    }

    @Test
    @DisplayName("a blank display name falls back to the email rather than an empty header")
    void blankNameFallsBackToEmail() {
        String email = uniqueEmail();
        User user = userService.upsertGoogleUser("sub-" + email, email, "  ", null);

        assertThat(user.getDisplayName()).isEqualTo(email);
    }

    @Test
    @DisplayName("lookup by id works and a missing id is reported, not silently null")
    void lookupById() {
        String email = uniqueEmail();
        User user = userService.upsertGoogleUser("sub-" + email, email, "Amr", null);

        assertThat(userService.findById(user.getId()))
                .get()
                .extracting(User::getId)
                .isEqualTo(user.getId());
        assertThat(userService.findById(UUID.randomUUID())).isEmpty();
        assertThat(userService.require(user.getId()).getEmail()).isEqualTo(email);
        assertThatThrownBy(() -> userService.require(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("recording a login updates the timestamp and ignores unknown ids")
    void recordLogin() {
        String email = uniqueEmail();
        User user = userService.upsertGoogleUser("sub-" + email, email, "Amr", null);

        userService.recordLogin(user.getId());
        userService.recordLogin(UUID.randomUUID()); // must not throw

        assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }
}
