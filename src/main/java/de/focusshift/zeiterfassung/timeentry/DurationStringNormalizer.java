package de.focusshift.zeiterfassung.timeentry;

/**
 * Normalizes a user-entered duration string into the canonical {@code HH:mm} form so that the
 * leading zero no longer has to be typed by hand.
 *
 * <p>Accepted inputs (examples):
 * <ul>
 *     <li>{@code "8"} → {@code "08:00"} (hours only)</li>
 *     <li>{@code "830"} → {@code "08:30"} (digits only, the last two digits are the minutes)</li>
 *     <li>{@code "8:30"} / {@code "08:30"} → {@code "08:30"}</li>
 *     <li>{@code ":30"} / {@code "030"} → {@code "00:30"}</li>
 *     <li>{@code "8,5"} / {@code "8.5"} → {@code "08:30"} (decimal hours, comma or dot)</li>
 * </ul>
 *
 * <p>Anything that cannot be interpreted is returned unchanged, leaving the existing
 * {@code @Pattern} bean validation to reject it.
 */
final class DurationStringNormalizer {

    private DurationStringNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }

        final String s = raw.trim();
        if (s.isEmpty()) {
            return s;
        }

        final boolean hasColon = s.indexOf(':') >= 0;
        final boolean hasDecimal = s.indexOf(',') >= 0 || s.indexOf('.') >= 0;

        // decimal hours, e.g. "8,5" or "8.5" → 08:30
        if (hasDecimal && !hasColon) {
            try {
                final double hours = Double.parseDouble(s.replace(',', '.'));
                if (hours < 0) {
                    return s;
                }
                final long totalMinutes = Math.round(hours * 60);
                return format(totalMinutes / 60, totalMinutes % 60);
            } catch (NumberFormatException e) {
                return s;
            }
        }

        // explicit colon, e.g. "8:30", "8:3", ":30"
        if (hasColon) {
            final String[] parts = s.split(":", -1);
            if (parts.length != 2) {
                return s;
            }
            final String h = parts[0].isEmpty() ? "0" : parts[0];
            final String m = parts[1].isEmpty() ? "0" : parts[1];
            if (!h.matches("\\d{1,2}") || !m.matches("\\d{1,2}")) {
                return s;
            }
            final long minutes = Long.parseLong(m);
            if (minutes > 59) {
                return s;
            }
            return format(Long.parseLong(h), minutes);
        }

        // digits only, e.g. "8", "830", "1230"
        if (s.matches("\\d{1,4}")) {
            final long h;
            final long m;
            if (s.length() <= 2) {
                h = Long.parseLong(s);
                m = 0;
            } else {
                h = Long.parseLong(s.substring(0, s.length() - 2));
                m = Long.parseLong(s.substring(s.length() - 2));
            }
            if (m > 59) {
                return s;
            }
            return format(h, m);
        }

        return s;
    }

    private static String format(long hours, long minutes) {
        return String.format("%02d:%02d", hours, minutes);
    }
}
