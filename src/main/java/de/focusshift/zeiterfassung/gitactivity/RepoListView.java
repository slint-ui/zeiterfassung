package de.focusshift.zeiterfassung.gitactivity;

import java.util.List;

/**
 * Bounded, display-ready list of repositories the app can read for one connected account,
 * rendered lazily on the account page.
 *
 * @param repos     the (capped) repositories
 * @param truncated true when the platform has more than we fetched
 * @param error     non-null human-readable message when the platform call failed; repos is empty
 */
public record RepoListView(List<RepoRef> repos, boolean truncated, String error) {

    public static RepoListView of(List<RepoRef> repos, boolean truncated) {
        return new RepoListView(repos, truncated, null);
    }

    public static RepoListView empty() {
        return new RepoListView(List.of(), false, null);
    }

    public static RepoListView error(String message) {
        return new RepoListView(List.of(), false, message);
    }
}
