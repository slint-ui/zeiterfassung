package de.focusshift.zeiterfassung.gitactivity;

import org.slf4j.Logger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Fetches Bitbucket activity (PRs, reviews, commits) for all users who have
 * connected their Bitbucket account via OAuth.
 *
 * <p>Because Bitbucket has no Events API — and removed the user-centric PR endpoint
 * ({@code GET /2.0/pullrequests/{account}}, now 404) — we list the repositories the user can
 * read and poll each one's pull requests (filtered to the user as author), deriving activity
 * from the current PR state and per-PR activity feeds.
 *
 * <p>Required configuration:
 * <pre>
 *   bitbucket.oauth.key    — OAuth consumer key (used for token refresh)
 *   bitbucket.oauth.secret — OAuth consumer secret
 * </pre>
 */
@Service
public class BitbucketActivityProvider implements GitActivityProvider {

    private static final Logger LOG = getLogger(lookup().lookupClass());
    private static final String PLATFORM = "BITBUCKET";
    /** Cap on repos queried per sync — bounds Bitbucket API usage (one PR-list call per repo). */
    private static final int MAX_REPOS_PER_SYNC = 50;

    private final GitOAuthTokenRepository tokenRepository;
    private final GitActivityRawEventRepository eventRepository;
    private final GitActivityPlatformSettingsService platformSettingsService;
    private final RestClient restClient;

    private final ConcurrentHashMap<String, Instant> lastSyncTimes = new ConcurrentHashMap<>();

    BitbucketActivityProvider(GitOAuthTokenRepository tokenRepository,
                               GitActivityRawEventRepository eventRepository,
                               GitActivityPlatformSettingsService platformSettingsService) {
        this.tokenRepository = tokenRepository;
        this.eventRepository = eventRepository;
        this.platformSettingsService = platformSettingsService;
        this.restClient = RestClient.builder()
            .defaultHeader("User-Agent", "zeiterfassung-bitbucket-sync")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    @Override public String platform() { return PLATFORM; }

    @Override
    public boolean isConfigured() {
        return platformSettingsService.getBitbucketSettings().isConfigured();
    }

    @Override
    public String missingConfig() {
        final GitActivityPlatformSettings s = platformSettingsService.getBitbucketSettings();
        final List<String> missing = new ArrayList<>();
        if (s.appId() == null || s.appId().isBlank())         missing.add("OAuth consumer key");
        if (s.appSecret() == null || s.appSecret().isBlank()) missing.add("OAuth consumer secret");
        return String.join(", ", missing);
    }

    @Override
    public List<String> resolveUsernames() {
        return tokenRepository.findByPlatform(PLATFORM).stream()
            .map(GitOAuthTokenEntity::getPlatformAccountId)
            .toList();
    }

    @Override
    public void syncUser(String platformAccountId) {
        final GitOAuthTokenEntity token = tokenRepository
            .findByPlatformAndPlatformAccountId(PLATFORM, platformAccountId)
            .orElse(null);
        if (token == null) {
            LOG.warn("No Bitbucket token found for account {}", platformAccountId);
            return;
        }

        final String accessToken;
        try {
            accessToken = ensureFreshToken(token);
        } catch (Exception e) {
            LOG.error("Failed to refresh Bitbucket token for {}", platformAccountId, e);
            return;
        }

        try {
            syncPullRequests(platformAccountId, accessToken);
            lastSyncTimes.put(platformAccountId, Instant.now());
        } catch (Exception e) {
            LOG.error("Failed to sync Bitbucket activity for {}", platformAccountId, e);
        }
    }

    @Override
    public Instant getLastSyncTime(String platformAccountId) {
        return lastSyncTimes.get(platformAccountId);
    }

    /**
     * Lists repositories the connected user can read, for the account page's "repositories the
     * app can read" disclosure. Bounded by {@code max}.
     *
     * <p>Workspace-scoped: Bitbucket removed the cross-workspace listing/enumeration endpoints
     * (CHANGE-2770), so the repos come from the admin-configured workspace(s) — see
     * {@link #fetchAccessibleRepos}.
     */
    public RepoListView listRepositories(Long userLocalId, int max) {
        final GitOAuthTokenEntity token = tokenRepository
            .findByPlatformAndUserLocalId(PLATFORM, userLocalId)
            .orElse(null);
        if (token == null) {
            return RepoListView.empty();
        }
        final String accessToken;
        try {
            accessToken = ensureFreshToken(token);
        } catch (Exception e) {
            LOG.warn("Bitbucket repo list: token refresh failed for user {}", userLocalId, e);
            return RepoListView.error("Couldn't load repositories - please reconnect.");
        }
        try {
            final List<Map<String, Object>> repos = fetchAccessibleRepos(accessToken, max);
            final List<RepoRef> refs = repos.stream()
                .map(BitbucketActivityProvider::toRepoRef)
                .toList();
            return RepoListView.of(refs, repos.size() >= max);
        } catch (Exception e) {
            LOG.warn("Bitbucket repo list failed for user {}", userLocalId, e);
            return RepoListView.error("Couldn't load repositories.");
        }
    }

    /**
     * Lists repositories the user can read, as raw Bitbucket repo objects, capped at {@code max}.
     *
     * <p>Bitbucket removed every cross-workspace listing endpoint — including workspace enumeration
     * ({@code /2.0/workspaces} and {@code /2.0/user/permissions/workspaces}, both 410, CHANGE-2770) —
     * so there is no API way to discover a user's workspaces. We scan the admin-configured
     * workspace(s) via {@code GET /2.0/repositories/{workspace}} (which is still supported).
     */
    private List<Map<String, Object>> fetchAccessibleRepos(String token, int max) {
        final List<String> workspaces = parseWorkspaces(
            platformSettingsService.getBitbucketSettings().orgName());
        if (workspaces.isEmpty()) {
            LOG.warn("No Bitbucket workspace configured (Settings -> Git Activity -> Workspace slug); "
                + "cannot list repositories — Bitbucket removed workspace discovery (CHANGE-2770).");
            return List.of();
        }
        final List<Map<String, Object>> repos = new ArrayList<>();
        for (String workspace : workspaces) {
            if (repos.size() >= max) break;
            for (Map<String, Object> repo : fetchPaged(
                    "https://api.bitbucket.org/2.0/repositories/" + workspace + "?pagelen=" + max, token, 1)) {
                if (repos.size() >= max) break;
                repos.add(repo);
            }
        }
        return repos;
    }

    /**
     * Parses the admin "Workspace slug" setting (comma/space-separated) into workspace slugs.
     * Package-private for tests.
     */
    static List<String> parseWorkspaces(String workspaceSetting) {
        if (workspaceSetting == null || workspaceSetting.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(workspaceSetting.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /** Maps one Bitbucket repository object to a {@link RepoRef}. Package-private for tests. */
    static RepoRef toRepoRef(Map<String, Object> repo) {
        return new RepoRef(
            strOrEmpty(repo.get("full_name")),
            linkHref(repo.get("links"), "html"),
            Boolean.TRUE.equals(repo.get("is_private")));
    }

    @SuppressWarnings("unchecked")
    private static String linkHref(Object linksObj, String key) {
        if (!(linksObj instanceof Map<?, ?> links)) return null;
        return ((Map<String, Object>) links).get(key) instanceof Map<?, ?> m
            ? (String) ((Map<String, Object>) m).get("href") : null;
    }

    // ── Token management ────────────────────────────────────────────────────

    private synchronized String ensureFreshToken(GitOAuthTokenEntity token) {
        if (!token.isExpired()) {
            return token.getAccessToken();
        }
        if (token.getRefreshToken() == null) {
            throw new IllegalStateException("Token expired and no refresh token available for " + token.getPlatformAccountId());
        }

        LOG.debug("Refreshing Bitbucket token for {}", token.getPlatformAccountId());
        final GitActivityPlatformSettings settings = platformSettingsService.getBitbucketSettings();
        final String credentials = Base64.getEncoder().encodeToString(
            (settings.appId() + ":" + settings.appSecret()).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        final Map<String, Object> response = restClient.post()
            .uri("https://bitbucket.org/site/oauth2/access_token")
            .header("Authorization", "Basic " + credentials)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=refresh_token&refresh_token=" + token.getRefreshToken())
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

        if (response == null) throw new IllegalStateException("Empty refresh response for " + token.getPlatformAccountId());

        final String newAccess  = (String) response.get("access_token");
        final String newRefresh = (String) response.get("refresh_token");
        final Number expiresIn  = (Number) response.get("expires_in");

        token.setAccessToken(newAccess);
        if (newRefresh != null) token.setRefreshToken(newRefresh);
        if (expiresIn != null) token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
        tokenRepository.save(token);

        return newAccess;
    }

    // ── Sync logic ──────────────────────────────────────────────────────────

    private void syncPullRequests(String accountId, String token) {
        // PRs updated in the last 90 days, so we pick up recently merged/declined ones too.
        final String cutoff = Instant.now().minus(90, ChronoUnit.DAYS)
            .toString().replace("Z", "+00:00");

        // The user-centric GET /2.0/pullrequests/{account} endpoint was removed (404), so list the
        // repos the user can read (workspace-scoped) and query each for PRs they authored.
        final List<String> repoFullNames = extractRepoFullNames(
            fetchAccessibleRepos(token, MAX_REPOS_PER_SYNC), MAX_REPOS_PER_SYNC);

        int saved = 0;
        for (String repoFullName : repoFullNames) {
            final String[] parts = repoFullName.split("/", 2);
            if (parts.length == 2) {
                saved += syncRepoPullRequests(accountId, parts[0], parts[1], cutoff, token);
            }
        }

        if (saved > 0) {
            LOG.info("Synced {} new Bitbucket event(s) for user {} across {} repo(s)",
                saved, accountId, repoFullNames.size());
        }
    }

    /** Extracts repo full-names ({@code workspace/repo}) from raw Bitbucket repo objects. Package-private for tests. */
    static List<String> extractRepoFullNames(List<Map<String, Object>> repos, int max) {
        final List<String> names = new ArrayList<>();
        for (Map<String, Object> repo : repos) {
            if (names.size() >= max) break;
            final String fullName = strOrEmpty(repo.get("full_name"));
            if (!fullName.isEmpty()) {
                names.add(fullName);
            }
        }
        return names;
    }

    /** Fetches the user's PRs in one repo (any state, last 90 days) and persists derived events. */
    private int syncRepoPullRequests(String accountId, String workspace, String repoSlug,
                                     String cutoff, String token) {
        final String q = "author.account_id=\"" + accountId + "\" AND updated_on>\"" + cutoff + "\"";
        final String url = "https://api.bitbucket.org/2.0/repositories/" + workspace + "/" + repoSlug
            + "/pullrequests?state=OPEN&state=MERGED&state=DECLINED&state=SUPERSEDED"
            + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20")
            + "&sort=-updated_on&pagelen=50";

        int saved = 0;
        for (Map<String, Object> pr : fetchPaged(url, token, 1)) {
            saved += processPr(pr, accountId, token);
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private int processPr(Map<String, Object> pr, String accountId, String token) {
        final int prId = toInt(pr.get("id"));
        if (prId == 0) return 0;

        final String title  = strOrEmpty(pr.get("title"));
        final String state  = strOrEmpty(pr.get("state"));   // OPEN, MERGED, DECLINED, SUPERSEDED

        final Map<String, Object> dest = (Map<String, Object>) pr.get("destination");
        final Map<String, Object> destRepo = dest != null ? (Map<String, Object>) dest.get("repository") : null;
        final String repoFullName = destRepo != null ? strOrEmpty(destRepo.get("full_name")) : "unknown/unknown";

        final Map<String, Object> source = (Map<String, Object>) pr.get("source");
        final Map<String, Object> srcBranch = source != null ? (Map<String, Object>) source.get("branch") : null;
        final String headBranch = srcBranch != null ? strOrEmpty(srcBranch.get("name")) : null;

        final Map<String, Object> srcRepo = source != null ? (Map<String, Object>) source.get("repository") : null;
        final String headRepoFullName = srcRepo != null ? strOrEmpty(srcRepo.get("full_name")) : null;

        // Use updated_on for merged/declined, created_on for open PRs
        final String rawTs = "OPEN".equals(state)
            ? strOrEmpty(pr.get("created_on"))
            : strOrEmpty(pr.get("updated_on"));
        final Instant ts = rawTs.isEmpty() ? Instant.now() : parseTs(rawTs);

        final String verb = switch (state) {
            case "MERGED"     -> "Merged";
            case "DECLINED"   -> "Closed";
            case "SUPERSEDED" -> "Superseded";
            default           -> "Opened";
        };
        final String summary = verb + " PR #" + prId + (title.isEmpty() ? "" : ": " + title);
        final String prEventId = accountId + "_bitbucket_pr_" + repoFullName.replace("/", "_") + "_" + prId;

        int saved = 0;

        final var existing = eventRepository.findByPlatformEventId(prEventId);
        if (existing.isPresent()) {
            final GitActivityRawEventEntity e = existing.get();
            if (applyPrUpdate(e, title, summary, verb, state, ts)) {
                eventRepository.save(e);
            }
        } else {
            final GitActivityRawEventEntity e = new GitActivityRawEventEntity();
            e.setPlatformEventId(prEventId);
            e.setPlatformUsername(accountId);
            e.setPlatform(PLATFORM);
            e.setEventType("PullRequestEvent");
            e.setRepoName(repoFullName);
            e.setAnchorType("PR");
            e.setAnchorId(String.valueOf(prId));
            e.setAnchorTitle(title);
            e.setEventIcon("🔀");
            e.setEventSummary(summary);
            e.setEventTimestamp(ts);
            e.setHeadBranch(headBranch);
            if (headRepoFullName != null && !headRepoFullName.equals(repoFullName)) {
                e.setHeadRepoName(headRepoFullName);
            }
            eventRepository.save(e);
            saved++;
        }

        // Commits and approvals: OPEN PRs only.
        // Comments: OPEN + recently-closed PRs (so comments on just-merged PRs are captured).
        final boolean isOpen = "OPEN".equals(state);
        final boolean isRecentlyClosed = !isOpen && ts.isAfter(Instant.now().minus(14, ChronoUnit.DAYS));
        if (isOpen || isRecentlyClosed) {
            final String[] repoParts = repoFullName.split("/", 2);
            if (repoParts.length == 2) {
                final String ws = repoParts[0];
                final String slug = repoParts[1];
                if (isOpen) {
                    saved += syncPrCommits(accountId, repoFullName, ws, slug, prId, headBranch, token);
                    saved += syncPrActivity(accountId, repoFullName, ws, slug, prId, title, token);
                }
                saved += syncPrComments(accountId, repoFullName, ws, slug, prId, title, token);
            }
        }

        return saved;
    }

    /**
     * Reflects late changes to an existing PR event: the title can be edited while the PR is open,
     * and the state can transition (merged/closed) since the last sync. Mutates {@code e} and
     * returns true if anything changed. Package-private for tests.
     */
    static boolean applyPrUpdate(GitActivityRawEventEntity e, String title, String summary,
                                 String verb, String state, Instant ts) {
        final boolean stateChanged = !e.getEventSummary().startsWith(verb);
        final boolean isStateTransition = stateChanged && !"OPEN".equals(state);
        final String currentTitle = e.getAnchorTitle() == null ? "" : e.getAnchorTitle();
        final boolean titleChanged = !title.equals(currentTitle);
        if (!titleChanged && !isStateTransition) {
            return false;
        }
        e.setAnchorTitle(title);
        e.setEventSummary(summary);
        if (isStateTransition) {
            e.setEventTimestamp(ts);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private int syncPrCommits(String accountId, String repoFullName,
                               String workspace, String repoSlug,
                               int prId, String headBranch, String token) {
        final String url = "https://api.bitbucket.org/2.0/repositories/"
            + workspace + "/" + repoSlug + "/pullrequests/" + prId + "/commits?pagelen=50";

        final List<Map<String, Object>> commits = fetchPaged(url, token, 1);
        int saved = 0;

        for (Map<String, Object> commit : commits) {
            final String hash = strOrEmpty(commit.get("hash"));
            if (hash.isEmpty()) continue;

            // Author filter — only store commits by this user
            final Map<String, Object> authorInfo = (Map<String, Object>) commit.get("author");
            if (authorInfo != null) {
                final Map<String, Object> authorUser = (Map<String, Object>) authorInfo.get("user");
                if (authorUser != null) {
                    final String authorAccountId = strOrEmpty(authorUser.get("account_id"));
                    if (!authorAccountId.isEmpty() && !authorAccountId.equals(accountId)) continue;
                }
            }

            final String message = firstLine(strOrEmpty(commit.get("message")), 200);
            final String dateStr = strOrEmpty(commit.get("date"));
            final Instant ts = dateStr.isEmpty() ? Instant.now() : parseTs(dateStr);
            final String eventId = accountId + "_bitbucket_commit_" + hash;
            final String shortHash = hash.substring(0, Math.min(7, hash.length()));

            final var existing = eventRepository.findByPlatformEventId(eventId);
            if (existing.isPresent()) {
                final GitActivityRawEventEntity ex = existing.get();
                if (!ex.getEventTimestamp().equals(ts)) {
                    ex.setEventTimestamp(ts);
                    eventRepository.save(ex);
                }
            } else {
                final GitActivityRawEventEntity e = new GitActivityRawEventEntity();
                e.setPlatformEventId(eventId);
                e.setPlatformUsername(accountId);
                e.setPlatform(PLATFORM);
                e.setEventType("PushEvent");
                e.setRepoName(repoFullName);
                e.setAnchorType("REPO");
                e.setAnchorId(headBranch);
                e.setAnchorTitle(headBranch);
                e.setEventIcon("📝");
                e.setEventSummary(message.isEmpty() ? shortHash : message);
                e.setEventTimestamp(ts);
                eventRepository.save(e);
                saved++;
            }
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private int syncPrActivity(String accountId, String repoFullName,
                                String workspace, String repoSlug,
                                int prId, String prTitle, String token) {
        final String url = "https://api.bitbucket.org/2.0/repositories/"
            + workspace + "/" + repoSlug + "/pullrequests/" + prId + "/activity?pagelen=50";

        final List<Map<String, Object>> activities = fetchPaged(url, token, 1);
        int saved = 0;

        for (Map<String, Object> activity : activities) {
            // Approval
            final Map<String, Object> approval = (Map<String, Object>) activity.get("approval");
            if (approval != null) {
                final Map<String, Object> user = (Map<String, Object>) approval.get("user");
                final String approverAccountId = user != null ? strOrEmpty(user.get("account_id")) : "";
                if (!approverAccountId.isEmpty() && approverAccountId.equals(accountId)) {
                    final String approvalId = accountId + "_bitbucket_pr_"
                        + repoFullName.replace("/", "_") + "_" + prId + "_approval";
                    if (!eventRepository.existsByPlatformEventId(approvalId)) {
                        final String dateStr = strOrEmpty(approval.get("date"));
                        final Instant ts = dateStr.isEmpty() ? Instant.now() : parseTs(dateStr);
                        final GitActivityRawEventEntity e = new GitActivityRawEventEntity();
                        e.setPlatformEventId(approvalId);
                        e.setPlatformUsername(accountId);
                        e.setPlatform(PLATFORM);
                        e.setEventType("PullRequestReviewEvent");
                        e.setRepoName(repoFullName);
                        e.setAnchorType("PR");
                        e.setAnchorId(String.valueOf(prId));
                        e.setAnchorTitle(prTitle);
                        e.setEventIcon("👁");
                        e.setEventSummary("Approved PR #" + prId
                            + (prTitle.isEmpty() ? "" : ": " + prTitle));
                        e.setEventTimestamp(ts);
                        eventRepository.save(e);
                        saved++;
                    }
                }
            }

        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private int syncPrComments(String accountId, String repoFullName,
                                String workspace, String repoSlug,
                                int prId, String prTitle, String token) {
        final String url = "https://api.bitbucket.org/2.0/repositories/"
            + workspace + "/" + repoSlug + "/pullrequests/" + prId + "/comments?pagelen=50";

        // Up to 3 pages (150 comments) — covers large code-review PRs without unbounded API calls.
        // The /comments endpoint (unlike /activity) includes inline diff comments and also returns
        // pending (batched) comments when authenticated as the author.
        final List<Map<String, Object>> comments = fetchPaged(url, token, 3);
        int saved = 0;

        for (Map<String, Object> comment : comments) {
            if (Boolean.TRUE.equals(comment.get("deleted"))) continue;

            final Map<String, Object> user = (Map<String, Object>) comment.get("user");
            final String commenterAccountId = user != null ? strOrEmpty(user.get("account_id")) : "";
            if (commenterAccountId.isEmpty() || !commenterAccountId.equals(accountId)) continue;

            final int commentId = toInt(comment.get("id"));
            if (commentId == 0) continue;

            final boolean isDraft = Boolean.TRUE.equals(comment.get("pending"));
            final String commentEventId = accountId + "_bitbucket_pr_"
                + repoFullName.replace("/", "_") + "_" + prId + "_comment_" + commentId;

            final String dateStr = strOrEmpty(comment.get("created_on"));
            final Instant ts = dateStr.isEmpty() ? Instant.now() : parseTs(dateStr);
            final Map<String, Object> content = (Map<String, Object>) comment.get("content");
            final String body = content != null ? firstLine(strOrEmpty(content.get("raw")), 120) : "";
            final String icon = isDraft ? "✏️" : "💬";
            final String verb = isDraft ? "Started draft comment on PR #" : "Commented on PR #";
            final String summary = verb + prId
                + (prTitle.isEmpty() ? "" : ": " + prTitle)
                + (body.isEmpty() ? "" : " — " + body);

            final var existing = eventRepository.findByPlatformEventId(commentEventId);
            if (existing.isPresent()) {
                // Draft → submitted: update the event so the activity feed reflects the final state.
                if (!isDraft && existing.get().getEventIcon().equals("✏️")) {
                    final GitActivityRawEventEntity e = existing.get();
                    e.setEventIcon(icon);
                    e.setEventSummary(summary);
                    eventRepository.save(e);
                }
                continue;
            }

            final GitActivityRawEventEntity e = new GitActivityRawEventEntity();
            e.setPlatformEventId(commentEventId);
            e.setPlatformUsername(accountId);
            e.setPlatform(PLATFORM);
            e.setEventType("PullRequestReviewCommentEvent");
            e.setRepoName(repoFullName);
            e.setAnchorType("PR");
            e.setAnchorId(String.valueOf(prId));
            e.setAnchorTitle(prTitle);
            e.setEventIcon(icon);
            e.setEventSummary(summary);
            e.setEventTimestamp(ts);
            eventRepository.save(e);
            saved++;
        }
        return saved;
    }

    // ── Pagination ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPaged(String firstUrl, String token, int maxPages) {
        final List<Map<String, Object>> result = new ArrayList<>();
        String nextUrl = firstUrl;
        int page = 0;

        while (nextUrl != null && page < maxPages) {
            try {
                final Map<String, Object> response = restClient.get()
                    // Pass a URI, NOT a String. RestClient.uri(String) re-encodes the '%' in an
                    // already-encoded URL (double-encoding), which breaks the BBQL `q` filter:
                    // Bitbucket sees %253D... -> decodes once -> "%3D" -> 400 "Illegal character in
                    // input '%3D%22...'". URI.create() parses the encoded URL as-is, no re-encoding.
                    .uri(java.net.URI.create(nextUrl))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

                if (response == null) break;

                final Object values = response.get("values");
                if (values instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) result.add((Map<String, Object>) m);
                    }
                }

                nextUrl = (String) response.get("next");
                page++;
            } catch (Exception e) {
                LOG.warn("Bitbucket API call failed for {}: {}", nextUrl, e.getMessage());
                break;
            }
        }

        return result;
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private static Instant parseTs(String s) {
        try {
            // Bitbucket returns ISO 8601 with optional space separator and timezone offset,
            // e.g. "2026-06-04T14:32:00.000000+05:30". OffsetDateTime handles all offsets
            // correctly; the previous string-replace approach replaced non-UTC offsets with Z,
            // silently shifting timestamps by the offset amount.
            return OffsetDateTime.parse(s.replace(" ", "T")).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private static String strOrEmpty(Object o) {
        return o instanceof String s ? s : "";
    }

    private static String firstLine(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        final String first = s.split("\n")[0].trim();
        return first.length() > maxLen ? first.substring(0, maxLen) : first;
    }
}
