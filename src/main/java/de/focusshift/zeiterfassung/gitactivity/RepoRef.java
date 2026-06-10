package de.focusshift.zeiterfassung.gitactivity;

/** A repository the app can read, shown on the account page's "repositories" disclosure. */
public record RepoRef(String fullName, String url, boolean privateRepo) {}
