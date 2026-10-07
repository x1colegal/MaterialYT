package com.liskovsoft.sharedutils.mylogger;

/** Android log adapter retained to keep the transport independent from the app module. */
public final class Log {
    private Log() { }
    public static void d(final String tag, final String format, final Object... args) {
        android.util.Log.d(tag, format(format, args));
    }
    public static void e(final String tag, final String format, final Object... args) {
        android.util.Log.e(tag, format(format, args));
    }
    public static void w(final String tag, final String format, final Object... args) {
        android.util.Log.w(tag, format(format, args));
    }
    private static String format(final String value, final Object... args) {
        try { return String.format(value, args); } catch (final RuntimeException ignored) { return value; }
    }
}
