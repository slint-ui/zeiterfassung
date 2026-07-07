package de.focusshift.zeiterfassung.user;

public enum TimeFormat {
    HOURS_24,
    HOURS_12;

    public String pattern() {
        return this == HOURS_12 ? "h:mm a" : "HH:mm";
    }
}
