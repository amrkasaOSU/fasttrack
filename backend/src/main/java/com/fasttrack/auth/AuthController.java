package com.fasttrack.auth;

import com.fasttrack.account.User;
import com.fasttrack.common.NotConfiguredException;
import com.fasttrack.config.AppProperties;
import com.fasttrack.demo.DemoAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session lifecycle.
 *
 * <p>{@code POST /api/auth/logout} is handled by Spring Security's logout
 * filter (see {@link SecurityConfig}) so the session is destroyed in Redis and
 * the cookies are cleared.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Sign-in state, demo session, sign-out")
public class AuthController {

    private final DemoAccountService demoAccounts;
    private final AuthorizationService authorization;
    private final SecurityContextRepository securityContextRepository;
    private final AppProperties props;

    public AuthController(DemoAccountService demoAccounts,
                          AuthorizationService authorization,
                          SecurityContextRepository securityContextRepository,
                          AppProperties props) {
        this.demoAccounts = demoAccounts;
        this.authorization = authorization;
        this.securityContextRepository = securityContextRepository;
        this.props = props;
    }

    public record CurrentUserResponse(
            boolean authenticated,
            UUID userId,
            String email,
            String displayName,
            String pictureUrl,
            boolean demo,
            boolean googleLoginAvailable) {}

    @GetMapping("/me")
    @Operation(summary = "Who the session belongs to (200 with authenticated=false when signed out)")
    public CurrentUserResponse me() {
        AppUserPrincipal principal = authorization.currentPrincipalOrNull();
        boolean googleAvailable = props.getGoogle().isConfigured();
        if (principal == null) {
            return new CurrentUserResponse(false, null, null, null, null, false, googleAvailable);
        }
        return new CurrentUserResponse(true,
                principal.userId(),
                principal.email(),
                principal.displayName(),
                principal.pictureUrl(),
                principal.demo(),
                googleAvailable);
    }

    /**
     * Starts a session as the shared demo account - the "Try the demo" button.
     * No credentials, no Google, no email access.
     */
    @PostMapping("/demo")
    @Operation(summary = "Start a session as the seeded demo user")
    public CurrentUserResponse startDemoSession(HttpServletRequest request, HttpServletResponse response) {
        if (!props.isDemoEnabled()) {
            throw new NotConfiguredException("demo", "Demo mode is turned off on this deployment.");
        }
        User demo = demoAccounts.ensureDemoUser();

        AuthenticatedUser authentication = new AuthenticatedUser(AppUserPrincipal.from(demo));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Session fixation: a brand new session id is issued for the demo login.
        request.getSession(true).invalidate();
        request.getSession(true);
        securityContextRepository.saveContext(context, request, response);

        return me();
    }
}
