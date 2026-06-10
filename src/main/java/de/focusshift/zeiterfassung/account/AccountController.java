package de.focusshift.zeiterfassung.account;

import de.focus_shift.launchpad.api.HasLaunchpad;
import de.focusshift.zeiterfassung.gitactivity.BitbucketActivityProvider;
import de.focusshift.zeiterfassung.gitactivity.GitActivityPlatformSettings;
import de.focusshift.zeiterfassung.gitactivity.GitActivityPlatformSettingsService;
import de.focusshift.zeiterfassung.gitactivity.GitHubActivityProvider;
import de.focusshift.zeiterfassung.gitactivity.GitOAuthTokenEntity;
import de.focusshift.zeiterfassung.gitactivity.GitOAuthTokenRepository;
import de.focusshift.zeiterfassung.gitactivity.RepoListView;
import de.focusshift.zeiterfassung.search.HasUserSearch;
import de.focusshift.zeiterfassung.search.UserSearchViewHelper;
import de.focusshift.zeiterfassung.security.CurrentUser;
import de.focusshift.zeiterfassung.security.oidc.CurrentOidcUser;
import de.focusshift.zeiterfassung.timeclock.HasTimeClock;
import de.focusshift.zeiterfassung.user.UserSettings;
import de.focusshift.zeiterfassung.user.UserSettingsService;
import org.slf4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import static de.focusshift.zeiterfassung.search.UserSearchViewHelper.USER_SEARCH_QUERY_PARAM;
import static de.focusshift.zeiterfassung.web.HotwiredTurboConstants.TURBO_FRAME_HEADER;
import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

@Controller
@RequestMapping("/account")
class AccountController implements HasTimeClock, HasLaunchpad, HasUserSearch {

    private static final Logger LOG = getLogger(lookup().lookupClass());

    private final UserSettingsService userSettingsService;
    private final UserSearchViewHelper userSearchViewHelper;
    private final GitOAuthTokenRepository gitOAuthTokenRepository;
    private final GitActivityPlatformSettingsService platformSettingsService;
    private final BitbucketActivityProvider bitbucketActivityProvider;
    private final GitHubActivityProvider gitHubActivityProvider;

    AccountController(UserSettingsService userSettingsService,
                      UserSearchViewHelper userSearchViewHelper,
                      GitOAuthTokenRepository gitOAuthTokenRepository,
                      GitActivityPlatformSettingsService platformSettingsService,
                      BitbucketActivityProvider bitbucketActivityProvider,
                      GitHubActivityProvider gitHubActivityProvider) {
        this.userSettingsService = userSettingsService;
        this.userSearchViewHelper = userSearchViewHelper;
        this.gitOAuthTokenRepository = gitOAuthTokenRepository;
        this.platformSettingsService = platformSettingsService;
        this.bitbucketActivityProvider = bitbucketActivityProvider;
        this.gitHubActivityProvider = gitHubActivityProvider;
    }

    @GetMapping
    ModelAndView account(Model model, @CurrentUser CurrentOidcUser currentOidcUser) {
        final UserSettings userSettings = userSettingsService.getUserSettings(currentOidcUser.getUserIdComposite());
        final Long userLocalId = currentOidcUser.getUserIdComposite().localId().value();
        populateModel(model, currentOidcUser, userSettings, userLocalId, false);
        return new ModelAndView("account/index", model.asMap());
    }

    @GetMapping(params = USER_SEARCH_QUERY_PARAM, headers = TURBO_FRAME_HEADER)
    ModelAndView userSearchFragment(@RequestParam(USER_SEARCH_QUERY_PARAM) String query,
                                    @CurrentUser CurrentOidcUser currentUser, Model model) {
        return userSearchViewHelper.getSuggestionFragment(query, currentUser, model,
            suggestion -> suggestion.userIdComposite().equals(currentUser.getUserIdComposite())
                ? "/timeentries"
                : "/timeentries/users/%s".formatted(suggestion.userLocalId().value())
        );
    }

    /**
     * Saves notification preferences. The GitHub identity is managed separately via the OAuth
     * verify flow and is intentionally NOT settable here — a client must not be able to mark a
     * login as verified, which is what previously allowed spoofing and duplicate verified logins.
     */
    @PostMapping
    ModelAndView saveAccount(@RequestParam(value = "notificationsEnabled", required = false, defaultValue = "false") boolean notificationsEnabled,
                             @RequestParam(value = "showStandaloneCommits", required = false, defaultValue = "false") boolean showStandaloneCommits,
                             @RequestParam(value = "reminderDaysBeforeLock", required = false, defaultValue = "2") int reminderDaysBeforeLock,
                             @CurrentUser CurrentOidcUser currentOidcUser, Model model) {
        userSettingsService.updateNotificationsEnabled(currentOidcUser.getUserIdComposite(), notificationsEnabled);
        userSettingsService.updateShowStandaloneCommits(currentOidcUser.getUserIdComposite(), showStandaloneCommits);
        userSettingsService.updateReminderDaysBeforeLock(currentOidcUser.getUserIdComposite(), reminderDaysBeforeLock);
        final Long userLocalId = currentOidcUser.getUserIdComposite().localId().value();
        final UserSettings refreshed = userSettingsService.getUserSettings(currentOidcUser.getUserIdComposite());
        populateModel(model, currentOidcUser, refreshed, userLocalId, true);
        return new ModelAndView("account/index", model.asMap());
    }

    // ── GitHub personal installation (customer repos) ─────────────────────────

    @GetMapping("/github/connect")
    String githubConnect(@CurrentUser CurrentOidcUser currentOidcUser) {
        final GitActivityPlatformSettings gh = platformSettingsService.getGitHubSettings();
        if (!gh.isPersonalInstallConfigured()) {
            return "redirect:/account?githubConnectError=not-configured";
        }
        return "redirect:https://github.com/apps/" + gh.appName()
            + "/installations/new?target_type=User";
    }

    /**
     * GitHub App setup URL — GitHub redirects here after the user installs the app.
     * Configure this URL in the GitHub App settings as the "Setup URL".
     */
    @GetMapping("/github/installed")
    String githubInstalled(@RequestParam(value = "installation_id", required = false) Long installationId,
                           @RequestParam(value = "setup_action", required = false, defaultValue = "install") String setupAction,
                           @CurrentUser CurrentOidcUser currentOidcUser) {
        if (installationId == null) {
            return "redirect:/account?githubConnectError=no-installation-id";
        }
        userSettingsService.updateGithubInstallationId(currentOidcUser.getUserIdComposite(), installationId);
        LOG.info("GitHub personal installation {} connected for user {}",
            installationId, currentOidcUser.getUserIdComposite().localId().value());
        return "redirect:/account?githubPersonalConnected=true";
    }

    @PostMapping("/github/disconnect-personal")
    String githubDisconnectPersonal(@CurrentUser CurrentOidcUser currentOidcUser) {
        userSettingsService.updateGithubInstallationId(currentOidcUser.getUserIdComposite(), null);
        return "redirect:/account";
    }

    // ── "Repositories the app can read" — lazy fragments loaded by a Turbo Frame ───────────────

    /** Lazy fragment: repositories the user's Bitbucket OAuth token can read. */
    @GetMapping("/bitbucket/repositories")
    String bitbucketRepositories(@CurrentUser CurrentOidcUser currentOidcUser, Model model) {
        final Long userLocalId = currentOidcUser.getUserIdComposite().localId().value();
        model.addAttribute("frameId", "bitbucket-repos");
        model.addAttribute("repoView", bitbucketActivityProvider.listRepositories(userLocalId, 30));
        model.addAttribute("platformName", "Bitbucket");
        model.addAttribute("moreUrl", "https://bitbucket.org/dashboard/repositories");
        return "account/fragments/repo-list :: repos";
    }

    /** Lazy fragment: repositories the user's GitHub App personal installation can read. */
    @GetMapping("/github/repositories")
    String githubRepositories(@CurrentUser CurrentOidcUser currentOidcUser, Model model) {
        final UserSettings userSettings = userSettingsService.getUserSettings(currentOidcUser.getUserIdComposite());
        final Long installationId = userSettings.githubInstallationId().orElse(null);
        final RepoListView view = installationId != null
            ? gitHubActivityProvider.listInstallationRepositories(installationId, 30)
            : RepoListView.empty();
        model.addAttribute("frameId", "github-repos");
        model.addAttribute("repoView", view);
        model.addAttribute("platformName", "GitHub");
        model.addAttribute("moreUrl", installationId != null
            ? "https://github.com/settings/installations/" + installationId
            : "https://github.com/settings/installations");
        return "account/fragments/repo-list :: repos";
    }

    private void populateModel(Model model, CurrentOidcUser currentOidcUser,
                               UserSettings userSettings, Long userLocalId, boolean savedSuccess) {
        final String fullName = currentOidcUser.getUserInfo() != null
            ? currentOidcUser.getUserInfo().getFullName()
            : currentOidcUser.getName();
        final String email = currentOidcUser.getUserInfo() != null
            ? currentOidcUser.getUserInfo().getEmail()
            : null;

        final GitActivityPlatformSettings gh = platformSettingsService.getGitHubSettings();
        final GitActivityPlatformSettings bb = platformSettingsService.getBitbucketSettings();
        final var bitbucketToken = gitOAuthTokenRepository
            .findByPlatformAndUserLocalId("BITBUCKET", userLocalId);

        model.addAttribute("fullName", fullName);
        model.addAttribute("email", email);
        model.addAttribute("githubLogin", userSettings.githubLogin().orElse(""));
        model.addAttribute("githubLoginVerified", userSettings.githubLoginVerified());
        model.addAttribute("notificationsEnabled", userSettings.notificationsEnabled());
        model.addAttribute("showStandaloneCommits", userSettings.showStandaloneCommits());
        model.addAttribute("reminderDaysBeforeLock", userSettings.reminderDaysBeforeLock());
        // GitHub identity verification (OAuth) + personal install (customer repos)
        model.addAttribute("githubUserOAuthConfigured", gh.isUserOAuthConfigured());
        model.addAttribute("githubPersonalInstallConfigured", gh.isPersonalInstallConfigured());
        model.addAttribute("githubInstallationId", userSettings.githubInstallationId().orElse(null));
        // Bitbucket
        model.addAttribute("bitbucketConfigured", bb.isConfigured());
        model.addAttribute("bitbucketConnected", bitbucketToken.isPresent());
        model.addAttribute("bitbucketDisplayName", bitbucketToken.map(GitOAuthTokenEntity::getDisplayName).orElse(null));
        model.addAttribute("bitbucketNickname", bitbucketToken.map(GitOAuthTokenEntity::getNickname).orElse(null));
        model.addAttribute("bitbucketAvatarUrl", bitbucketToken.map(GitOAuthTokenEntity::getAvatarUrl).orElse(null));
        model.addAttribute("bitbucketProfileUrl", bitbucketToken.map(GitOAuthTokenEntity::getProfileUrl).orElse(null));
        model.addAttribute("savedSuccess", savedSuccess);
    }
}
