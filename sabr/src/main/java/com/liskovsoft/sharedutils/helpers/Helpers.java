package com.liskovsoft.sharedutils.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Minimal, dependency-free helpers required by the SABR transport. */
public final class Helpers {
    private Helpers() { }

    public static int parseInt(final String value) { return parseInt(value, 0); }
    public static int parseInt(final String value, final int fallback) {
        try { return Integer.parseInt(value); } catch (final RuntimeException error) { return fallback; }
    }
    public static long parseLong(final String value) { return parseLong(value, 0L); }
    public static long parseLong(final String value, final long fallback) {
        try { return Long.parseLong(value); } catch (final RuntimeException error) { return fallback; }
    }
    public static float parseFloat(final String value, final float fallback) {
        try { return Float.parseFloat(value); } catch (final RuntimeException error) { return fallback; }
    }
    public static boolean isNumeric(final String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }
    public static boolean equals(final Object first, final Object second) {
        return Objects.equals(first, second);
    }
    public static <T> T findFirst(final Iterable<T> values, final Predicate<T> predicate) {
        for (final T value : values) if (predicate.test(value)) return value;
        return null;
    }
    public static List<Integer> range(final int start, final int end, final int step) {
        final List<Integer> values = new ArrayList<>();
        if (step <= 0) return values;
        for (int value = start; value < end; value += step) values.add(value);
        return values;
    }
}
