package de.focusshift.zeiterfassung.user;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public record UserSettingsDto(String theme, @Nullable Locale locale, String timeFormat) {

    public UserSettingsDto(String theme) {
        this(theme, null, TimeFormat.HOURS_24.name());
    }
}
