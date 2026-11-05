package com.andunie.reminder;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses typed times: delays ("45", "45m", "1h30", "2h 15m", "in 3 days", "5s") and clock times
 * ("14:30", "9am", "9:15 pm", "at 7"). Plain Java with no Android dependencies.
 */
final class Times {
    private static final long SECOND = 1000L, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    private static final long MAX_DELAY = 366 * DAY;

    private static final Pattern DELAY_PART = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)?\\s*");
    // A clock time needs ":", am/pm or a leading "at"; otherwise a bare number is a delay in minutes.
    private static final Pattern CLOCK = Pattern.compile(
            "(at\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a|p)?");

    private Times() {}

    /**
     * A delay or a clock time, whichever the text is.
     *
     * @return the epoch millisecond it denotes, or -1 if the text isn't understood.
     */
    static long parse(String s, long now, boolean is24h) {
        long at = parseClock(s, now, is24h);
        if (at >= 0) return at;
        long delay = parseDelay(s);
        return delay < 0 ? -1 : now + delay;
    }

    /**
     * Sum of number+unit parts; a number without a unit is minutes ("1h30" is 90 minutes).
     *
     * @return the delay in milliseconds, or -1 if the text isn't a positive delay.
     */
    static long parseDelay(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("in ")) s = s.substring(3).trim();
        if (s.isEmpty()) return -1;
        Matcher m = DELAY_PART.matcher(s);
        double total = 0;
        int pos = 0;
        while (pos < s.length()) {
            if (!m.find(pos) || m.start() != pos || m.end() == pos) return -1;
            double n = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2);
            if (unit == null) {
                total += n * MINUTE;
            } else {
                switch (unit.charAt(0)) {
                    case 'd': total += n * DAY; break;
                    case 'h': total += n * HOUR; break;
                    case 's': total += n * SECOND; break;
                    default: total += n * MINUTE;
                }
            }
            pos = m.end();
        }
        long ms = Math.round(total);
        return ms >= SECOND && ms <= MAX_DELAY ? ms : -1;
    }

    /**
     * The next occurrence of a time of day: today if it's still ahead, else tomorrow. On a 12-hour
     * clock "9:30" without am/pm means whichever of 9:30 and 21:30 comes first.
     *
     * @return the epoch millisecond, or -1 if the text isn't a clock time.
     */
    static long parseClock(String s, long now, boolean is24h) {
        Matcher m = CLOCK.matcher(s.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return -1;
        boolean at = m.group(1) != null;
        String ampm = m.group(4);
        if (!at && m.group(3) == null && ampm == null) return -1;
        int hour = Integer.parseInt(m.group(2));
        int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (minute > 59) return -1;
        if (ampm != null) {
            if (hour < 1 || hour > 12) return -1;
            hour = hour % 12 + (ampm.startsWith("p") ? 12 : 0);
            return next(now, hour, minute);
        }
        if (hour > 23) return -1;
        long first = next(now, hour, minute);
        if (is24h || hour < 1 || hour > 12) return first;
        return Math.min(first, next(now, hour % 12 + 12, minute));
    }

    /** The next time the clock reads hour:minute, strictly after {@code now}. */
    static long next(long now, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** "45 s", "10 min", "1 hour", "2 hours", "1h 30m", "2d 3h"; rounded to the minute past 1 minute. */
    static String duration(long ms) {
        if (ms < MINUTE) return Math.max(1, Math.round(ms / (double) SECOND)) + " s";
        long minutes = Math.round(ms / (double) MINUTE);
        long d = minutes / (24 * 60), h = minutes / 60 % 24, min = minutes % 60;
        if (d == 0 && h == 0) return min + " min";
        if (d == 0 && min == 0) return h + (h == 1 ? " hour" : " hours");
        if (d > 0 && h == 0 && min == 0) return d + (d == 1 ? " day" : " days");
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (min > 0) sb.append(min).append("m ");
        return sb.toString().trim();
    }
}
