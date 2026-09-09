package pl.commercelink.starter.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeOrderedFileNameTest {

    @Test
    void buildsCountdownAndReadableTimestampFromInstant() {
        Instant instant = Instant.parse("2025-03-04T05:06:07Z");

        assertEquals("8258935232_2025-03-04_05-06-07", TimeOrderedFileName.of(instant));
    }

    @Test
    void laterInstantSortsBeforeEarlierAsPlainString() {
        String earlier = TimeOrderedFileName.of(Instant.parse("2025-03-04T05:06:07Z"));
        String later = TimeOrderedFileName.of(Instant.parse("2025-03-04T05:06:08Z"));

        assertTrue(later.compareTo(earlier) < 0, later + " should sort before " + earlier);
    }

    @Test
    void newestOfManyNamesIsFirstInLexicographicOrder() {
        Instant base = Instant.parse("2025-03-04T05:06:07Z");
        String oldest = TimeOrderedFileName.of(base);
        String middle = TimeOrderedFileName.of(base.plus(1, ChronoUnit.HOURS));
        String newest = TimeOrderedFileName.of(base.plus(400, ChronoUnit.DAYS));

        assertEquals(newest, Stream.of(middle, oldest, newest).sorted().findFirst().orElseThrow());
    }

    @Test
    void recoversInstantFromNameTruncatedToSeconds() {
        Instant instant = Instant.parse("2025-03-04T05:06:07.123Z");

        assertEquals(Optional.of(instant.truncatedTo(ChronoUnit.SECONDS)),
                TimeOrderedFileName.instantOf(TimeOrderedFileName.of(instant)));
    }

    @Test
    void recoversInstantFromNameCarryingAdditionalParts() {
        Instant instant = Instant.parse("2025-03-04T05:06:07Z");
        String fileName = TimeOrderedFileName.of(instant) + "_1cb4e2b6-4a2d-4c6e-9a0f-8b0f0f2b1d55.csv";

        assertEquals(Optional.of(instant), TimeOrderedFileName.instantOf(fileName));
    }

    @Test
    void reportsInstantAbsentForNameWithoutScheme() {
        assertEquals(Optional.empty(), TimeOrderedFileName.instantOf("1cb4e2b6-4a2d-4c6e-9a0f-8b0f0f2b1d55.csv"));
        assertEquals(Optional.empty(), TimeOrderedFileName.instantOf("2025-03-04_05-06-07"));
        assertEquals(Optional.empty(), TimeOrderedFileName.instantOf(""));
        assertEquals(Optional.empty(), TimeOrderedFileName.instantOf(null));
    }

    @Test
    void extractsReadableTimestampFromName() {
        String fileName = TimeOrderedFileName.of(Instant.parse("2025-03-04T05:06:07Z")) + ".csv";

        assertEquals(Optional.of("2025-03-04 05:06:07"), TimeOrderedFileName.readableTimestampOf(fileName));
    }

    @Test
    void reportsReadableTimestampAbsentForNameWithoutScheme() {
        assertEquals(Optional.empty(), TimeOrderedFileName.readableTimestampOf("1cb4e2b6-4a2d.csv"));
        assertEquals(Optional.empty(), TimeOrderedFileName.readableTimestampOf(null));
    }

    @Test
    void reportsReadableTimestampAbsentForImpossibleDate() {
        assertEquals(Optional.empty(), TimeOrderedFileName.readableTimestampOf("0000000000_2025-13-45_05-06-07"));
    }
}
