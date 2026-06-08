package de.focusshift.zeiterfassung.user;

import de.focusshift.zeiterfassung.usermanagement.UserLocalId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Optional;

import static de.focusshift.zeiterfassung.user.Theme.LIGHT;
import static java.util.Locale.ENGLISH;
import static java.util.Locale.GERMAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    private UserSettingsService sut;

    @Mock
    private UserSettingsRepository userSettingsRepository;
    @Mock
    private LocaleResolver localeResolver;

    @BeforeEach
    void setUp() {
        sut = new UserSettingsService(userSettingsRepository, localeResolver);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Nested
    class GetUserSettings {

        @Test
        void ensureUserSettingsForPerson() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.DARK);
            entity.setLocale(GERMAN);

            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));

            final UserSettings actual = sut.getUserSettings(userIdComposite);

            assertThat(actual.theme()).isEqualTo(Theme.DARK);
            assertThat(actual.locale()).hasValue(GERMAN);
        }

        @Test
        void ensureUserSettingsForPersonReturnsDefault() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findById(localId)).thenReturn(Optional.empty());

            final UserSettings actual = sut.getUserSettings(userIdComposite);

            assertThat(actual.theme()).isEqualTo(Theme.SYSTEM);
            assertThat(actual.locale()).isEmpty();
        }
    }

    @Nested
    class UpdateUserPreferences {

        @Test
        void ensureUpdateUserPreference() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.DARK);
            entity.setLocale(null);
            entity.setLocaleBrowserSpecific(ENGLISH);

            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));

            final UserSettingsEntity entityToSave = new UserSettingsEntity();
            entityToSave.setTenantUserLocalId(localId);
            entityToSave.setTheme(LIGHT);
            entityToSave.setLocale(GERMAN);

            when(userSettingsRepository.save(entityToSave)).thenReturn(entityToSave);

            final UserSettings updatedUserSettings = sut.updateUserPreference(userIdComposite, LIGHT, GERMAN);
            assertThat(updatedUserSettings.theme()).isEqualTo(LIGHT);
            assertThat(updatedUserSettings.locale()).hasValue(GERMAN);
            assertThat(updatedUserSettings.localeBrowserSpecific()).isEmpty();

            final ArgumentCaptor<UserSettingsEntity> entityArgumentCaptor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(entityArgumentCaptor.capture());
            assertThat(entityArgumentCaptor.getValue())
                .satisfies(userSettingsEntity -> {
                    assertThat(userSettingsEntity.getTheme()).isEqualTo(LIGHT);
                    assertThat(userSettingsEntity.getLocale()).isEqualTo(GERMAN);
                    assertThat(userSettingsEntity.getLocaleBrowserSpecific()).isNull();
                });
        }

        @Test
        void ensureUpdateUserPreferenceUsesBrowserSpecificLocaleLocaleWillBeNull() {

            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.addPreferredLocale(GERMAN);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.DARK);
            entity.setLocale(ENGLISH);
            entity.setLocaleBrowserSpecific(null);

            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));

            final UserSettingsEntity entityToSave = new UserSettingsEntity();
            entityToSave.setTenantUserLocalId(localId);
            entityToSave.setTheme(LIGHT);
            entityToSave.setLocale(null);
            entityToSave.setLocaleBrowserSpecific(GERMAN);

            when(userSettingsRepository.save(entityToSave)).thenReturn(entityToSave);

            final UserSettings updatedUserSettings = sut.updateUserPreference(userIdComposite, LIGHT, null);
            assertThat(updatedUserSettings.theme()).isEqualTo(LIGHT);
            assertThat(updatedUserSettings.locale()).isEmpty();
            assertThat(updatedUserSettings.localeBrowserSpecific()).hasValue(GERMAN);

            final ArgumentCaptor<UserSettingsEntity> entityArgumentCaptor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(entityArgumentCaptor.capture());
            assertThat(entityArgumentCaptor.getValue())
                .satisfies(userSettingsEntity -> {
                    assertThat(userSettingsEntity.getTheme()).isEqualTo(LIGHT);
                    assertThat(userSettingsEntity.getLocale()).isNull();
                    assertThat(userSettingsEntity.getLocaleBrowserSpecific()).isEqualTo(GERMAN);
                });
        }

        @Test
        void ensureUpdateUserPreferenceWhenNothingHasBeenPersistedYet() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findById(localId)).thenReturn(Optional.empty());

            final UserSettingsEntity entityToSave = new UserSettingsEntity();
            entityToSave.setTenantUserLocalId(localId);
            entityToSave.setTheme(LIGHT);
            entityToSave.setLocale(GERMAN);

            when(userSettingsRepository.save(entityToSave)).thenReturn(entityToSave);

            final UserSettings updatedUserSettings = sut.updateUserPreference(userIdComposite, LIGHT, GERMAN);
            assertThat(updatedUserSettings.theme()).isEqualTo(LIGHT);

            final ArgumentCaptor<UserSettingsEntity> entityArgumentCaptor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(entityArgumentCaptor.capture());
            assertThat(entityArgumentCaptor.getValue())
                .satisfies(userSettingsEntity -> {
                    assertThat(userSettingsEntity.getTheme()).isEqualTo(LIGHT);
                    assertThat(userSettingsEntity.getLocale()).isEqualTo(GERMAN);
                    assertThat(userSettingsEntity.getLocaleBrowserSpecific()).isNull();
                });
        }
    }

    @Nested
    class FindTheme {

        @Test
        void ensureFindThemeForUsernameReturnsEmptyOptionalWhenUsernameIsUnknown() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.empty());

            final Optional<Theme> actual = sut.findTheme(userIdComposite);
            assertThat(actual).isEmpty();
        }

        @Test
        void ensureFindThemeForUsernameReturnsEmptyOptionalWhenThereIsNoLocale() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTheme(null);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));

            final Optional<Theme> actual = sut.findTheme(userIdComposite);
            assertThat(actual).isEmpty();
        }

        @Test
        void ensureFindThemeForUsernameReturnsLocale() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTheme(Theme.DARK);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));

            final Optional<Theme> actual = sut.findTheme(userIdComposite);
            assertThat(actual).hasValue(Theme.DARK);
        }
    }

    @Nested
    class GetLocale {

        @Test
        void ensureGetLocaleReturnsEmptyOptionalWhenUserIdIsUnknown() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.empty());

            final Optional<java.util.Locale> actual = sut.getLocale(userIdComposite);
            assertThat(actual).isEmpty();
        }

        @Test
        void ensureGetLocaleReturnsEmptyOptionalWhenLocaleIsNull() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setLocale(null);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));

            final Optional<java.util.Locale> actual = sut.getLocale(userIdComposite);
            assertThat(actual).isEmpty();
        }

        @Test
        void ensureGetLocaleReturnsLocale() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setLocale(GERMAN);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));

            final Optional<java.util.Locale> actual = sut.getLocale(userIdComposite);
            assertThat(actual).hasValue(GERMAN);
        }
    }

    @Nested
    class UpdateLocaleBrowserSpecific {

        @Test
        void ensureUpdateLocaleBrowserSpecificWhenLocaleIsNull() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setLocale(null);
            entity.setLocaleBrowserSpecific(null);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));
            when(userSettingsRepository.save(entity)).thenReturn(entity);

            sut.updateLocaleBrowserSpecific(userIdComposite, GERMAN);

            final ArgumentCaptor<UserSettingsEntity> entityArgumentCaptor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(entityArgumentCaptor.capture());
            assertThat(entityArgumentCaptor.getValue())
                .satisfies(userSettingsEntity -> {
                    assertThat(userSettingsEntity.getLocaleBrowserSpecific()).isEqualTo(GERMAN);
                });
        }

        @Test
        void ensureUpdateLocaleBrowserSpecificDoesNotSaveWhenLocaleIsNotNull() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setLocale(ENGLISH);
            entity.setLocaleBrowserSpecific(null);

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.of(entity));

            sut.updateLocaleBrowserSpecific(userIdComposite, GERMAN);

            verify(userSettingsRepository, never()).save(entity);
        }

        @Test
        void ensureUpdateLocaleBrowserSpecificCreatesDefaultWhenNotFound() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findByTenantUserLocalId(localId)).thenReturn(Optional.empty());

            sut.updateLocaleBrowserSpecific(userIdComposite, GERMAN);

            final ArgumentCaptor<UserSettingsEntity> entityArgumentCaptor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(entityArgumentCaptor.capture());
            assertThat(entityArgumentCaptor.getValue())
                .satisfies(userSettingsEntity -> {
                    assertThat(userSettingsEntity.getTheme()).isEqualTo(Theme.SYSTEM);
                    assertThat(userSettingsEntity.getTenantUserLocalId()).isEqualTo(localId);
                    assertThat(userSettingsEntity.getLocaleBrowserSpecific()).isEqualTo(GERMAN);
                });
        }
    }

    @Nested
    class LinkVerifiedGithubLogin {

        @Test
        void ensureLinksLoginAsVerifiedWhenNotTaken() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue("octocat"))
                .thenReturn(Optional.empty());

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.SYSTEM);
            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));

            final boolean linked = sut.linkVerifiedGithubLogin(userIdComposite, "octocat");

            assertThat(linked).isTrue();
            final ArgumentCaptor<UserSettingsEntity> captor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(captor.capture());
            assertThat(captor.getValue().getGithubLogin()).isEqualTo("octocat");
            assertThat(captor.getValue().isGithubLoginVerified()).isTrue();
        }

        @Test
        void ensureAllowsSameUserToReverifyTheirOwnLogin() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity existing = new UserSettingsEntity();
            existing.setTenantUserLocalId(localId);
            existing.setTheme(Theme.SYSTEM);
            when(userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue("octocat"))
                .thenReturn(Optional.of(existing));
            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(existing));

            final boolean linked = sut.linkVerifiedGithubLogin(userIdComposite, "octocat");

            assertThat(linked).isTrue();
            verify(userSettingsRepository).save(existing);
        }

        @Test
        void ensureRejectsLoginAlreadyVerifiedByAnotherUser() {
            final UserIdComposite userIdComposite = anyUserIdComposite();

            final UserSettingsEntity other = new UserSettingsEntity();
            other.setTenantUserLocalId(2L);
            when(userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue("octocat"))
                .thenReturn(Optional.of(other));

            final boolean linked = sut.linkVerifiedGithubLogin(userIdComposite, "octocat");

            assertThat(linked).isFalse();
            verify(userSettingsRepository, never()).save(any());
        }

        @Test
        void ensureReturnsFalseWhenUniqueIndexRejectsConcurrentDuplicate() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            when(userSettingsRepository.findByGithubLoginAndGithubLoginVerifiedTrue("octocat"))
                .thenReturn(Optional.empty());
            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.SYSTEM);
            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));
            when(userSettingsRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

            final boolean linked = sut.linkVerifiedGithubLogin(userIdComposite, "octocat");

            assertThat(linked).isFalse();
        }
    }

    @Nested
    class ClearGithubLogin {

        @Test
        void ensureClearsLoginVerifiedFlagAndInstallation() {
            final UserIdComposite userIdComposite = anyUserIdComposite();
            final Long localId = userIdComposite.localId().value();

            final UserSettingsEntity entity = new UserSettingsEntity();
            entity.setTenantUserLocalId(localId);
            entity.setTheme(Theme.SYSTEM);
            entity.setGithubLogin("octocat");
            entity.setGithubLoginVerified(true);
            entity.setGithubInstallationId(123L);
            when(userSettingsRepository.findById(localId)).thenReturn(Optional.of(entity));
            when(userSettingsRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            sut.clearGithubLogin(userIdComposite);

            final ArgumentCaptor<UserSettingsEntity> captor = ArgumentCaptor.forClass(UserSettingsEntity.class);
            verify(userSettingsRepository).save(captor.capture());
            assertThat(captor.getValue().getGithubLogin()).isNull();
            assertThat(captor.getValue().isGithubLoginVerified()).isFalse();
            assertThat(captor.getValue().getGithubInstallationId()).isNull();
        }
    }

    private static UserIdComposite anyUserIdComposite() {
        return anyUserIdComposite(new UserId("uuid"));
    }

    private static UserIdComposite anyUserIdComposite(UserId userId) {
        return new UserIdComposite(userId, new UserLocalId(1L));
    }
}

