package de.focusshift.zeiterfassung.gitactivity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live smoke-test for the Bitbucket PR comments + activity endpoints.
 *
 *   BB_TOKEN=<token> ./mvnw -Dtest=BitbucketCommentsLiveIT test -Dskip.npm=true
 *
 * Token source: Bitbucket → Personal settings → App passwords
 *   (needs Repositories:Read + Pull requests:Read)
 * Or from the app DB:
 *   docker exec zeiterfassung-postgres-1 psql -U admin_user -d zeiterfassung \
 *     -c "SELECT access_token FROM git_oauth_token WHERE platform='BITBUCKET';"
 */
class BitbucketCommentsLiveIT {

    private static final String WORKSPACE = "slint-ui";
    private static final String REPO_SLUG = "test2";
    private static final int    PR_ID     = 2;

    private RestClient client;

    @Test
    void exploreCommentAndActivityEndpoints() {
        final String token = System.getenv("BB_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(),
            "BB_TOKEN not set — skipping live Bitbucket test");

        client = RestClient.builder()
            .defaultHeader("User-Agent", "zeiterfassung-bitbucket-sync")
            .defaultHeader("Accept", "application/json")
            .build();

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println(" /comments (published)");
        System.out.println("══════════════════════════════════════════════");
        final List<Map<String, Object>> published = fetchComments(token, "");
        printComments(published);

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println(" /comments?state=PENDING (draft / review)");
        System.out.println("══════════════════════════════════════════════");
        final List<Map<String, Object>> pending = fetchComments(token, "&q=state+%3D+%22PENDING%22");
        printComments(pending);

        System.out.println("\n══════════════════════════════════════════════");
        System.out.println(" /activity (approval + comment events)");
        System.out.println("══════════════════════════════════════════════");
        final List<Map<String, Object>> activities = fetch(
            "https://api.bitbucket.org/2.0/repositories/"
                + WORKSPACE + "/" + REPO_SLUG + "/pullrequests/" + PR_ID + "/activity?pagelen=50",
            token);
        printActivities(activities);

        final int total = published.size() + pending.size();
        assertThat(total)
            .as("expected at least one comment (published or pending) on PR #" + PR_ID)
            .isGreaterThan(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchComments(String token, String extraQuery) {
        final String url = "https://api.bitbucket.org/2.0/repositories/"
            + WORKSPACE + "/" + REPO_SLUG + "/pullrequests/" + PR_ID + "/comments?pagelen=50" + extraQuery;
        return fetch(url, token);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetch(String url, String token) {
        try {
            final Map<String, Object> response = client.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (response == null) return List.of();
            final Object values = response.get("values");
            return values instanceof List<?> list
                ? list.stream().filter(i -> i instanceof Map).map(i -> (Map<String, Object>) i).toList()
                : List.of();
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void printComments(List<Map<String, Object>> comments) {
        System.out.println("  count: " + comments.size());
        for (Map<String, Object> c : comments) {
            final Map<String, Object> user    = (Map<String, Object>) c.get("user");
            final Map<String, Object> content = (Map<String, Object>) c.get("content");
            System.out.printf("  id=%-8s  state=%-12s  deleted=%-5b  user=%s%n",
                c.get("id"),
                c.getOrDefault("state", "—"),
                Boolean.TRUE.equals(c.get("deleted")),
                user != null ? user.get("display_name") + " (" + user.get("account_id") + ")" : "?");
            System.out.println("    body: " + (content != null ? content.get("raw") : "—"));
        }
    }

    @SuppressWarnings("unchecked")
    private void printActivities(List<Map<String, Object>> activities) {
        System.out.println("  count: " + activities.size());
        for (Map<String, Object> a : activities) {
            if (a.containsKey("comment")) {
                final Map<String, Object> c    = (Map<String, Object>) a.get("comment");
                final Map<String, Object> user = (Map<String, Object>) c.get("user");
                final Map<String, Object> cnt  = (Map<String, Object>) c.get("content");
                System.out.printf("  [comment] id=%-8s  state=%-12s  deleted=%-5b  user=%s%n",
                    c.get("id"),
                    c.getOrDefault("state", "—"),
                    Boolean.TRUE.equals(c.get("deleted")),
                    user != null ? user.get("display_name") + " (" + user.get("account_id") + ")" : "?");
                System.out.println("    body: " + (cnt != null ? cnt.get("raw") : "—"));
            } else {
                final String kind = a.keySet().stream()
                    .filter(k -> !k.equals("pull_request")).findFirst().orElse("unknown");
                System.out.println("  [" + kind + "]");
            }
        }
    }
}
