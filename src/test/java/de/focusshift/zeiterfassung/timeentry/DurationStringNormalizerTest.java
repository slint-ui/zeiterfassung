package de.focusshift.zeiterfassung.timeentry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DurationStringNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
        // hours only
        "8, 08:00",
        "08, 08:00",
        "12, 12:00",
        "0, 00:00",
        // digits only — last two digits are the minutes
        "830, 08:30",
        "0830, 08:30",
        "1230, 12:30",
        "030, 00:30",
        // explicit colon, including single-digit hour and omitted parts
        "8:30, 08:30",
        "08:30, 08:30",
        "8:3, 08:03",
        "8:, 08:00",
        "':30', 00:30",
        // decimal hours with dot or comma
        "8.5, 08:30",
        "'8,5', 08:30",
        "'1,25', 01:15",
        "'0,5', 00:30",
        ".5, 00:30",
    })
    void normalizesToCanonicalHhmm(String input, String expected) {
        assertThat(DurationStringNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" stays unchanged")
    @CsvSource({
        "abc, abc",
        "12345, 12345",   // too many digits to be HH:mm
        "8:99, 8:99",     // minutes out of range
        "99:99, 99:99",   // minutes out of range
        "-5, -5",
    })
    void leavesUnparseableInputUnchanged(String input, String expected) {
        assertThat(DurationStringNormalizer.normalize(input)).isEqualTo(expected);
    }

    @Test
    void returnsNullForNull() {
        assertThat(DurationStringNormalizer.normalize(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void returnsEmptyStringForBlankInput(String input) {
        assertThat(DurationStringNormalizer.normalize(input)).isEmpty();
    }
}
