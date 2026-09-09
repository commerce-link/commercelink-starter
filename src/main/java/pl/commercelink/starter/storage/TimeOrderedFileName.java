package pl.commercelink.starter.storage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeOrderedFileName {

    private static final long COUNTDOWN_ORIGIN = 9_999_999_999L;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIMESTAMP_UTC = TIMESTAMP.withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter READABLE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern COUNTDOWN_PREFIX = Pattern.compile("^(\\d{10})_(.+)$");
    private static final Pattern TIMESTAMP_PART = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");

    private TimeOrderedFileName() {
    }

    public static String of(Instant instant) {
        return String.format("%010d_%s", COUNTDOWN_ORIGIN - instant.getEpochSecond(), TIMESTAMP_UTC.format(instant));
    }

    public static Optional<Instant> instantOf(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher countdown = COUNTDOWN_PREFIX.matcher(fileName);
        if (!countdown.matches()) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(COUNTDOWN_ORIGIN - Long.parseLong(countdown.group(1))));
    }

    public static Optional<String> readableTimestampOf(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher timestamp = TIMESTAMP_PART.matcher(fileName);
        if (!timestamp.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(timestamp.group(), TIMESTAMP).format(READABLE_TIMESTAMP));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
