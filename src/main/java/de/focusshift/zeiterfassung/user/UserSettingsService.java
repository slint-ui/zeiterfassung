package de.focusshift.zeiterfassung.user;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

@Service
public class UserSettingsService {

    private static final Logger LOG = getLogger(lookup().lookupClass());

    private final UserSettingsRepository userSettingsRepository;
    private final LocaleResolver localeResolver;

    UserSettingsService(UserSettingsRepository userSettingsRepository, LocaleResolver localeResolver) {
        this.userSettingsRepository = userSettingsRepository;
        this.localeResolver = localeResolver;
    }

    Optional<Theme> findTheme(UserIdComposite userIdComposite) {
        final Long localId = userIdComposite.localId().value();
        return userSettingsRepository.findByTenantUserLocalId(localId).map(UserSettingsEntity::getTheme);
    }

    Optional<TimeFormat> findTimeFormat(UserIdComposite userIdComposite) {
        final Long localId = userIdComposite.localId().value();
        return userSettingsRepository.findByTenantUserLocalId(localId).map(UserSettingsEntity::getTimeFormat);
    }

    Optional<Locale> getLocale(UserIdComposite userIdComposite) {
        final Long localId = userIdComposite.localId().value();
        return userSettingsRepository.findByTenantUserLocalId(localId).map(UserSettingsEntity::getLocale);
    }

    public UserSettings getUserSettings(UserIdComposite userIdComposite) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        return toUserSettings(entity);
    }

    /**
     * Updates the user settings of the person with the given attributes.
     * <p>
     * Also update the browser specific locale based on the given locale
     * and if false with the locale from the request.
     *
     * @param userIdComposite to update the {@link UserSettings} for.
     * @param theme  the {@link Theme} for the person.
     * @return the updated {@link UserSettings}
     */
    UserSettings updateUserPreference(UserIdComposite userIdComposite, Theme theme, @Nullable Locale locale, TimeFormat timeFormat) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setTenantUserLocalId(userIdComposite.localId().value());
        entity.setTheme(theme);
        entity.setLocale(locale);
        entity.setTimeFormat(timeFormat);

        final Locale localeFromRequest = locale == null ? getRequest().map(ServletRequest::getLocale).orElse(null) : null;
        entity.setLocaleBrowserSpecific(localeFromRequest);

        final UserSettingsEntity persistedEntity = userSettingsRepository.save(entity);
        LOG.info("Updated user settings to {}", persistedEntity);

        setLocale(persistedEntity.getLocale());

        return toUserSettings(persistedEntity);
    }

    /**
     * Sets the browser specific locale from the request.
     * <p>
     * Only saves the browser specific locale if the saved 'locale' is null.
     * If the saved 'locale' is null, that means, that the localization is based on the browser,
     * and therefore we save it to use it in e-mail templates e.g.
     *
     * @param userIdComposite       to save the browser specific locale
     * @param localeBrowserSpecific browser specific locale
     */
    void updateLocaleBrowserSpecific(UserIdComposite userIdComposite, Locale localeBrowserSpecific) {
        userSettingsRepository.findByTenantUserLocalId(userIdComposite.localId().value())
            .ifPresentOrElse(userSettingsEntity -> {
                if (userSettingsEntity.getLocale() == null) {
                    userSettingsEntity.setLocaleBrowserSpecific(localeBrowserSpecific);
                    userSettingsRepository.save(userSettingsEntity);
                }
            }, () -> {
                final UserSettingsEntity defaultUserSettingsEntity = defaultUserSettingsEntity(userIdComposite);
                defaultUserSettingsEntity.setLocaleBrowserSpecific(localeBrowserSpecific);
                userSettingsRepository.save(defaultUserSettingsEntity);
            });
    }

    private UserSettingsEntity findOrGetDefault(UserIdComposite userIdComposite) {
        final Long id = userIdComposite.localId().value();
        return userSettingsRepository.findById(id).orElseGet(() -> defaultUserSettingsEntity(userIdComposite));
    }

    private UserSettingsEntity defaultUserSettingsEntity(UserIdComposite userIdComposite) {
        final UserSettingsEntity userSettingsEntity = new UserSettingsEntity();
        userSettingsEntity.setTheme(Theme.SYSTEM);
        userSettingsEntity.setTenantUserLocalId(userIdComposite.localId().value());

        LOG.debug("created (not persisted) default userSettingsEntity={}", userSettingsEntity);

        return userSettingsEntity;
    }

    public List<String> findAllVerifiedGithubLogins() {
        return userSettingsRepository.findByGithubLoginVerifiedTrue().stream()
            .map(UserSettingsEntity::getGithubLogin)
            .filter(Objects::nonNull)
            .filter(login -> !login.isBlank())
            .toList();
    }

    /**
     * Links {@code githubLogin} to this user as a <em>verified</em> GitHub identity, but only if
     * it is not already verified by a different user. Returns {@code false} on conflict (the login
     * is already owned by someone else). This is the only path that may set the verified flag — it
     * is reached exclusively from the OAuth callback, where GitHub has proven ownership of the login.
     *
     * <p>The application-level check is a read-then-write and therefore racy; the unique index on
     * {@code (tenant_id, github_login) WHERE github_login_verified} backs it up, so a concurrent
     * duplicate surfaces as a {@link DataIntegrityViolationException} and is also treated as a conflict.
     */
    public boolean linkVerifiedGithubLogin(UserIdComposite userIdComposite, String githubLogin) {
        final Long localId = userIdComposite.localId().value();
        final Optional<UserSettingsEntity> existing =
            userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue(githubLogin);
        if (existing.isPresent() && !localId.equals(existing.get().getTenantUserLocalId())) {
            return false;
        }
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setGithubLogin(githubLogin);
        entity.setGithubLoginVerified(true);
        try {
            userSettingsRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            LOG.warn("Concurrent verify conflict for github login {}", githubLogin);
            return false;
        }
        LOG.info("Verified GitHub login for user {}", localId);
        return true;
    }

    /** Clears the user's GitHub identity — verified login and any personal app installation. */
    public UserSettings clearGithubLogin(UserIdComposite userIdComposite) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setGithubLogin(null);
        entity.setGithubLoginVerified(false);
        entity.setGithubInstallationId(null);
        final UserSettingsEntity persisted = userSettingsRepository.save(entity);
        LOG.info("Cleared GitHub login for user {}", userIdComposite.localId().value());
        return toUserSettings(persisted);
    }

    public UserSettings updateGithubToken(UserIdComposite userIdComposite, @Nullable String githubToken) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        final String trimmed = githubToken != null && !githubToken.isBlank() ? githubToken.trim() : null;
        entity.setGithubToken(trimmed);
        final UserSettingsEntity persistedEntity = userSettingsRepository.save(entity);
        LOG.info("Updated github token for user {}", userIdComposite.localId().value());
        return toUserSettings(persistedEntity);
    }

    public UserSettings updateNotificationsEnabled(UserIdComposite userIdComposite, boolean enabled) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setNotificationsEnabled(enabled);
        return toUserSettings(userSettingsRepository.save(entity));
    }

    /**
     * Stores (or clears) the GitHub App installation ID for the user's personal account.
     * Called after the user completes the GitHub App installation flow for customer repos.
     */
    public UserSettings updateGithubInstallationId(UserIdComposite userIdComposite, @Nullable Long installationId) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setGithubInstallationId(installationId);
        final UserSettingsEntity persisted = userSettingsRepository.save(entity);
        LOG.info("Updated GitHub installation ID for user {} to {}", userIdComposite.localId().value(), installationId);
        return toUserSettings(persisted);
    }

    /**
     * Looks up the personal GitHub App installation ID for the user identified by their
     * verified GitHub login. Used by the sync service to get a personal installation token.
     */
    public Optional<Long> findGithubInstallationIdByLogin(String githubLogin) {
        return userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue(githubLogin)
            .map(UserSettingsEntity::getGithubInstallationId);
    }

    public UserSettings updateShowStandaloneCommits(UserIdComposite userIdComposite, boolean show) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setShowStandaloneCommits(show);
        return toUserSettings(userSettingsRepository.save(entity));
    }

    public UserSettings updateReminderDaysBeforeLock(UserIdComposite userIdComposite, int days) {
        final UserSettingsEntity entity = findOrGetDefault(userIdComposite);
        entity.setReminderDaysBeforeLock(Math.max(1, days));
        return toUserSettings(userSettingsRepository.save(entity));
    }

    private static UserSettings toUserSettings(UserSettingsEntity userSettingsEntity) {
        return new UserSettings(
            userSettingsEntity.getTheme(),
            userSettingsEntity.getLocale(),
            userSettingsEntity.getLocaleBrowserSpecific(),
            userSettingsEntity.getGithubLogin(),
            userSettingsEntity.isGithubLoginVerified(),
            userSettingsEntity.getGithubToken(),
            userSettingsEntity.isNotificationsEnabled(),
            userSettingsEntity.getGithubInstallationId(),
            userSettingsEntity.isShowStandaloneCommits(),
            userSettingsEntity.getReminderDaysBeforeLock(),
            userSettingsEntity.getTimeFormat()
        );
    }

    private void setLocale(Locale locale) {
        getRequest().ifPresent(request -> localeResolver.setLocale(request, null, locale));
    }

    private Optional<HttpServletRequest> getRequest() {
        HttpServletRequest request = null;

        final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null) {
            request = ((ServletRequestAttributes) requestAttributes).getRequest();
        }

        return Optional.ofNullable(request);
    }
}
