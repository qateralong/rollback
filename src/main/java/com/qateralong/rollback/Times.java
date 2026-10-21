package com.qateralong.rollback;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Times {
    private static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SHOW_SECONDS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter ARGUMENT = DateTimeFormatter.ofPattern("dd.MM.yyyy_HH:mm", Locale.ROOT);
    private static final Pattern RELATIVE = Pattern.compile("-?(\\d+)\\s*(s|m|h|d|с|м|ч|д)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?[_ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private Times() {
    }

    public static ZoneId zone() {
        String id = RollbackConfig.get().timeZone;
        try {
            return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public static String format(long epochSeconds) {
        ZonedDateTime time = Instant.ofEpochSecond(epochSeconds).atZone(zone());
        return (time.getSecond() == 0 ? SHOW : SHOW_SECONDS).format(time);
    }

    public static String argument(long epochSeconds) {
        return ARGUMENT.format(Instant.ofEpochSecond(epochSeconds).atZone(zone()));
    }

    public static long parse(String text) {
        String value = text.trim();
        Matcher matcher = RELATIVE.matcher(value);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                case 's', 'с' -> 1L;
                case 'm', 'м' -> 60L;
                case 'h', 'ч' -> 3600L;
                default -> 86400L;
            };
            return Instant.now().getEpochSecond() - amount * unit;
        }
        matcher = FULL.matcher(value);
        if (matcher.matches()) {
            int year = matcher.group(3) == null ? LocalDate.now(zone()).getYear() : Integer.parseInt(matcher.group(3));
            LocalDateTime time = LocalDateTime.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)), matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)));
            return time.atZone(zone()).toEpochSecond();
        }
        matcher = TIME.matcher(value);
        if (matcher.matches()) {
            LocalTime clock = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
            ZonedDateTime time = LocalDate.now(zone()).atTime(clock).atZone(zone());
            if (time.toEpochSecond() > Instant.now().getEpochSecond()) {
                time = time.minusDays(1);
            }
            return time.toEpochSecond();
        }
        throw new IllegalArgumentException(value);
    }
}
