package de.focusshift.zeiterfassung.gitactivity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class BitbucketActivityProviderTest {

    @Mock private GitOAuthTokenRepository tokenRepository;
    @Mock private GitActivityRawEventRepository eventRepository;
    @Mock private GitActivityPlatformSettingsService platformSettingsService;
    @Mock @SuppressWarnings("rawtypes") private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock @SuppressWarnings("rawtypes") private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private BitbucketActivityProvider sut;

    @BeforeEach
    void setUp() {
        sut = new BitbucketActivityProvider(tokenRepository, eventRepository, platformSettingsService);
    }

    // ── parseTs / timezone handling ───────────────────────────────────────────

    @Nested
    class TimestampParsing {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            // UTC explicit
            "2026-06-04T14:32:00.000000+00:00, 2026-06-04T14:32:00Z",
            // Space separator instead of T
            "2026-06-04 14:32:00.000000+00:00, 2026-06-04T14:32:00Z",
            // Positive offset — was previously shifted by the offset amount
            "2026-06-04T14:32:00.000000+05:30, 2026-06-04T09:02:00Z",
            // Negative offset
            "2026-06-04T14:32:00.000000-08:00, 2026-06-04T22:32:00Z",
            // +00:00 format (already worked before the fix too)
            "2026-06-04T00:00:00.000000+00:00, 2026-06-04T00:00:00Z",
            // Positive half-hour offset (India Standard Time)
            "2026-06-04T09:00:00.000000+05:30, 2026-06-04T03:30:00Z",
        })
        void ensureTimestampIsCorrectlyConvertedToUtc(String input, String expectedUtc) throws Exception {
            final java.lang.reflect.Method parseTs = BitbucketActivityProvider.class
                .getDeclaredMethod("parseTs", String.class);
            parseTs.setAccessible(true);

            final Instant result = (Instant) parseTs.invoke(null, input);

            assertThat(result).isEqualTo(Instant.parse(expectedUtc));
        }

        @Test
        void ensureInvalidTimestampFallsBackToNow() throws Exception {
            final java.lang.reflect.Method parseTs = BitbucketActivityProvider.class
                .getDeclaredMethod("parseTs", String.class);
            parseTs.setAccessible(true);

            final Instant before = Instant.now();
            final Instant result = (Instant) parseTs.invoke(null, "not-a-date");
            final Instant after = Instant.now();

            assertThat(result).isBetween(before, after);
        }
    }

    // ── isConfigured ─────────────────────────────────────────────────────────

    @Nested
    class Configuration {

        @Test
        void ensureNotConfiguredWhenSettingsMissing() {
            when(platformSettingsService.getBitbucketSettings())
                .thenReturn(GitActivityPlatformSettings.empty("BITBUCKET"));

            assertThat(sut.isConfigured()).isFalse();
        }

        @Test
        void ensureConfiguredWhenBothKeyAndSecretPresent() {
            when(platformSettingsService.getBitbucketSettings())
                .thenReturn(new GitActivityPlatformSettings("BITBUCKET", "key", "secret", null, null, null, null, null));

            assertThat(sut.isConfigured()).isTrue();
        }
    }

    // ── resolveUsernames ──────────────────────────────────────────────────────

    @Nested
    class ResolveUsernames {

        @Test
        void ensureUsernamesComeFromOAuthTokens() {
            final GitOAuthTokenEntity token = new GitOAuthTokenEntity();
            token.setPlatform("BITBUCKET");
            token.setPlatformAccountId("account-123");
            when(tokenRepository.findByPlatform("BITBUCKET")).thenReturn(List.of(token));

            assertThat(sut.resolveUsernames()).containsExactly("account-123");
        }

        @Test
        void ensureEmptyListWhenNoTokensConnected() {
            when(tokenRepository.findByPlatform("BITBUCKET")).thenReturn(List.of());

            assertThat(sut.resolveUsernames()).isEmpty();
        }
    }

    // ── syncUser ──────────────────────────────────────────────────────────────

    @Nested
    class SyncUser {

        @Test
        void ensureSyncSkippedWhenNoTokenFound() {
            when(tokenRepository.findByPlatformAndPlatformAccountId("BITBUCKET", "acc-1"))
                .thenReturn(Optional.empty());

            sut.syncUser("acc-1");

            verify(eventRepository, never()).save(any());
        }
    }

    // ── listRepositories parsing ───────────────────────────────────────────────

    @Nested
    class ListRepositoriesParsing {

        // /2.0/user/permissions/repositories wraps each repo under a "repository" object.
        private Map<String, Object> repo(String fullName, boolean isPrivate, String htmlHref) {
            final Map<String, Object> r = new java.util.HashMap<>();
            r.put("full_name", fullName);
            r.put("is_private", isPrivate);
            if (htmlHref != null) {
                r.put("links", Map.of("html", Map.of("href", htmlHref)));
            }
            return Map.of("permission", "read", "repository", r);
        }

        @Test
        void mapsFullNamePrivateFlagAndHtmlUrl() {
            final Map<String, Object> response = Map.of("values", List.of(
                repo("acme/web", true, "https://bitbucket.org/acme/web"),
                repo("acme/docs", false, "https://bitbucket.org/acme/docs")));

            final RepoListView view = BitbucketActivityProvider.parseRepositories(response, 30);

            assertThat(view.error()).isNull();
            assertThat(view.truncated()).isFalse();
            assertThat(view.repos()).extracting(RepoRef::fullName).containsExactly("acme/web", "acme/docs");
            assertThat(view.repos()).extracting(RepoRef::url)
                .containsExactly("https://bitbucket.org/acme/web", "https://bitbucket.org/acme/docs");
            assertThat(view.repos()).extracting(RepoRef::privateRepo).containsExactly(true, false);
        }

        @Test
        void handlesMissingLinks() {
            final Map<String, Object> response = Map.of("values", List.of(repo("acme/web", false, null)));

            final RepoListView view = BitbucketActivityProvider.parseRepositories(response, 30);

            assertThat(view.repos()).hasSize(1);
            assertThat(view.repos().get(0).url()).isNull();
            assertThat(view.repos().get(0).fullName()).isEqualTo("acme/web");
        }

        @Test
        void capsAtMaxAndFlagsTruncatedWhenNextPresent() {
            final Map<String, Object> response = new java.util.HashMap<>();
            response.put("values", List.of(
                repo("a/1", false, "u1"), repo("a/2", false, "u2"), repo("a/3", false, "u3")));
            response.put("next", "https://api.bitbucket.org/2.0/repositories?page=2");

            final RepoListView view = BitbucketActivityProvider.parseRepositories(response, 2);

            assertThat(view.repos()).hasSize(2);
            assertThat(view.truncated()).isTrue();
        }

        @Test
        void notTruncatedWhenNoNextAndUnderMax() {
            final Map<String, Object> response = Map.of("values", List.of(repo("a/1", false, "u1")));

            assertThat(BitbucketActivityProvider.parseRepositories(response, 30).truncated()).isFalse();
        }

        @Test
        void emptyWhenResponseNull() {
            assertThat(BitbucketActivityProvider.parseRepositories(null, 30).repos()).isEmpty();
        }
    }
}
