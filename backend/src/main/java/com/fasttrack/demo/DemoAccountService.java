package com.fasttrack.demo;

import com.fasttrack.account.User;
import com.fasttrack.account.UserRepository;
import com.fasttrack.common.ForbiddenException;
import com.fasttrack.config.AppProperties;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The demo account.
 *
 * <p>It is a real row in {@code users} with {@code is_demo = true}, not a mock
 * layer: every demo page runs the same queries, the same state machine and the
 * same aggregations as a signed-in user (DECISIONS.md D-007). Its data is
 * seeded by {@code DemoDataSeeder}.
 */
@Service
public class DemoAccountService {

    public static final String DEMO_EMAIL = "demo@fasttrack.app";
    public static final String DEMO_NAME = "Demo User";

    private final UserRepository users;
    private final AppProperties props;

    public DemoAccountService(UserRepository users, AppProperties props) {
        this.users = users;
        this.props = props;
    }

    @Transactional
    public User ensureDemoUser() {
        if (!props.isDemoEnabled()) {
            throw new ForbiddenException("Demo mode is disabled on this deployment");
        }
        User user = users.findFirstByDemoIsTrue()
                .orElseGet(() -> users.save(User.demoUser(DEMO_EMAIL, DEMO_NAME)));
        user.setLastLoginAt(Instant.now());
        return user;
    }

    @Transactional(readOnly = true)
    public boolean demoExists() {
        return users.findFirstByDemoIsTrue().isPresent();
    }
}
