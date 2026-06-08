package de.focusshift.zeiterfassung.gitactivity;

import de.focusshift.zeiterfassung.security.CurrentUser;
import de.focusshift.zeiterfassung.security.oidc.CurrentOidcUser;
import de.focusshift.zeiterfassung.user.UserSettingsService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Handles the GitHub "verify your identity" flow via the GitHub App's user-authorization
 * (OAuth) endpoints.
 *
 * <p>Unlike the previous existence-check, this proves the signed-in user actually
 * <em>owns</em> the GitHub account: GitHub authenticates them and reports the login back to
 * us through {@code GET /user}, so the login cannot be spoofed by the client.
 *
 * <p>Required configuration (admin → Git Activity → GitHub):
 * <pre>
 *   client_id     — GitHub App OAuth client ID
 *   client_secret — GitHub App OAuth client secret
 * </pre>
 * The GitHub App must list {@code https://<host>/account/github/verify/callback} as a Callback URL.
 */
@Controller
@RequestMapping("/account/github")
class GitHubVerifyController {

    private static final Logger LOG = getLogger(lookup().lookupClass());

    private static final String SESSION_STATE_KEY = "github_verify_state";
    private static final String SESSION_USER_KEY  = "github_verify_user_id";

    private final GitActivityPlatformSettingsService platformSettingsService;
    private final UserSettingsService userSettingsService;
    private final RestClient restClient;

    GitHubVerifyController(GitActivityPlatformSettingsService platformSettingsService,
                           UserSettingsService userSettingsService) {
        this.platformSettingsService = platformSettingsService;
        this.userSettingsService = userSettingsService;
        this.restClient = RestClient.builder()
            .defaultHeader("User-Agent", "zeiterfassung-github-verify")
            .build();
    }

    /** Step 1 — redirect the user to GitHub to authorize. */
    @GetMapping("/verify")
    String start(@CurrentUser CurrentOidcUser currentUser, HttpSession session) {
        final GitActivityPlatformSettings settings = platformSettingsService.getGitHubSettings();
        if (!settings.isUserOAuthConfigured()) {
            LOG.warn("GitHub user OAuth (client id/secret) not configured in admin settings");
            return "redirect:/account?githubVerifyError=not-configured";
        }

        final String state = UUID.randomUUID().toString();
        session.setAttribute(SESSION_STATE_KEY, state);
        session.setAttribute(SESSION_USER_KEY, currentUser.getUserIdComposite().localId().value());

        final String authUrl = "https://github.com/login/oauth/authorize"
            + "?client_id=" + enc(settings.clientId())
            + "&state=" + enc(state);

        return "redirect:" + authUrl;
    }

    /** Step 2 — GitHub redirects back here; exchange the code and store the proven login. */
    @GetMapping("/verify/callback")
    String callback(@RequestParam(required = false) String code,
                    @RequestParam(required = false) String state,
                    @RequestParam(required = false) String error,
                    @CurrentUser CurrentOidcUser currentUser,
                    HttpSession session) {

        final String expectedState = (String) session.getAttribute(SESSION_STATE_KEY);
        final Long sessionUserId = (Long) session.getAttribute(SESSION_USER_KEY);
        session.removeAttribute(SESSION_STATE_KEY);
        session.removeAttribute(SESSION_USER_KEY);

        if (error != null) {
            LOG.warn("GitHub verify denied by user: {}", error);
            return "redirect:/account?githubVerifyError=access-denied";
        }

        final Long currentUserId = currentUser.getUserIdComposite().localId().value();
        if (expectedState == null || !expectedState.equals(state)
            || sessionUserId == null || !sessionUserId.equals(currentUserId)) {
            LOG.warn("GitHub verify state mismatch or missing session data");
            return "redirect:/account?githubVerifyError=state-mismatch";
        }

        if (code == null || code.isBlank()) {
            return "redirect:/account?githubVerifyError=no-code";
        }

        try {
            final GitActivityPlatformSettings settings = platformSettingsService.getGitHubSettings();
            final String token = exchangeCodeForUserToken(settings, code);
            if (token == null) {
                return "redirect:/account?githubVerifyError=token-exchange-failed";
            }
            final String login = fetchAuthenticatedLogin(token);
            if (login == null || login.isBlank()) {
                return "redirect:/account?githubVerifyError=user-fetch-failed";
            }
            final boolean linked = userSettingsService.linkVerifiedGithubLogin(currentUser.getUserIdComposite(), login);
            if (!linked) {
                LOG.info("GitHub login {} is already linked to another user", login);
                return "redirect:/account?githubVerifyError=already-linked";
            }
            LOG.info("GitHub login verified for user {}", currentUserId);
            return "redirect:/account?githubVerified=true";
        } catch (Exception e) {
            LOG.error("GitHub verification failed", e);
            return "redirect:/account?githubVerifyError=verify-failed";
        }
    }

    /** Removes the verified GitHub identity (and any personal installation) for the current user. */
    @PostMapping("/disconnect")
    String disconnect(@CurrentUser CurrentOidcUser currentUser) {
        userSettingsService.clearGithubLogin(currentUser.getUserIdComposite());
        LOG.info("GitHub login disconnected for user {}", currentUser.getUserIdComposite().localId().value());
        return "redirect:/account";
    }

    private String exchangeCodeForUserToken(GitActivityPlatformSettings settings, String code) {
        final String body = "client_id=" + enc(settings.clientId())
            + "&client_secret=" + enc(settings.clientSecret())
            + "&code=" + enc(code);
        final Map<String, Object> response = restClient.post()
            .uri("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(body)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        return response != null ? (String) response.get("access_token") : null;
    }

    private String fetchAuthenticatedLogin(String accessToken) {
        final Map<String, Object> user = restClient.get()
            .uri("https://api.github.com/user")
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        return user != null ? (String) user.get("login") : null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
