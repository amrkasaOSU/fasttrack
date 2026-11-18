package com.fasttrack.support;

import com.fasttrack.account.User;
import com.fasttrack.account.UserRepository;
import com.fasttrack.auth.AppUserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates throwaway accounts for tests that need two separate users. */
@Component
public class UserFixtures {

    private final UserRepository users;

    public UserFixtures(UserRepository users) {
        this.users = users;
    }

    public User newUser(String label) {
        String unique = label + "-" + UUID.randomUUID();
        return users.save(User.googleUser(unique + "@example.com", label, null, "sub-" + unique));
    }

    public AppUserPrincipal principal(User user) {
        return AppUserPrincipal.from(user);
    }

    public AppUserPrincipal newPrincipal(String label) {
        return principal(newUser(label));
    }
}
