package stroom.util.logging;

import stroom.util.concurrent.DurationAdder;

import com.google.common.base.Strings;
import org.slf4j.helpers.MessageFormatter;

import java.time.Duration;
import java.time.Instant;

public final class LogUtil {

    // These are 3 byte unicode chars so a bit of a waste of bytes
//    private static final char BOX_HORIZONTAL_LINE = '━';
//    private static final char BOX_VERTICAL_LINE = '┃';
//    private static final char BOX_BTM_LEFT = '┗';
//    private static final char BOX_TOP_LEFT = '┏';
//    private static final char BOX_BTM_RIGHT = '┛';
//    private static final char BOX_TOP_RIGHT = '┓';

    // These are in code page 437 (DOS latin US) + 805 (DOS latin 1)
//    private static final char BOX_HORIZONTAL_LINE = '═';
//    private static final char BOX_VERTICAL_LINE = '║';
//    private static final char BOX_BTM_LEFT = '╚';
//    private static final char BOX_TOP_LEFT = '╔';
//    private static final char BOX_BTM_RIGHT = '╝';
//    private static final char BOX_TOP_RIGHT = '╗';

    // These are in code page 437 (DOS latin US) + 805 (DOS latin 1)
    private static final char BOX_HORIZONTAL_LINE = '─';
    private static final char BOX_VERTICAL_LINE = '│';
    private static final char BOX_BTM_LEFT = '└';
    private static final char BOX_TOP_LEFT = '┌';
    private static final char BOX_BTM_RIGHT = '┘';
    private static final char BOX_TOP_RIGHT = '┐';

    private LogUtil() {
        // Utility class.
    }

    /**
     * Constructs a formatted message string using a format string that takes
     * the same placeholders as SLF4J, e.g.
     * "Function called with name {} and value {}"
     *
     * @param format SLF4J style format string
     * @param args   The values for any placeholders in the message format
     * @return A formatted message
     */
    public static String message(String format, Object... args) {
        return MessageFormatter.arrayFormat(format, args).getMessage();
    }

    /**
     * Constructs a formatted message string using a format string that takes
     * the same placeholders as SLF4J, e.g.
     * "Function called with name {} and value {}"
     * This constructed message is placed inside a separator line padded out to 100 chars, e.g.
     * === Function called with name foo and value bar ====================================================
     *
     * @param format SLF4J style format string
     * @param args   The values for any placeholders in the message format
     * @return A formatted message in a separator line
     */
    public static String inSeparatorLine(final String format, final Object... args) {
        final String text = message(format, args);
        final String str = Strings.repeat(String.valueOf(BOX_HORIZONTAL_LINE), 3)
                + " "
                + text;
        return Strings.padEnd(str, 100, BOX_HORIZONTAL_LINE);
    }

    /**
     * Constructs a formatted message string using a format string that takes
     * the same placeholders as SLF4J, e.g.
     * "Function called with name {} and value {}"
     * This constructed message is placed inside a box after a line break.
     *
     * @param format SLF4J style format string
     * @param args   The values for any placeholders in the message format
     * @return A formatted message in a box on a new line
     */
    public static String inBoxOnNewLine(final String format, final Object... args) {
        return inBox(format, true, args);
    }

    /**
     * Constructs a formatted message string using a format string that takes
     * the same placeholders as SLF4J, e.g.
     * "Function called with name {} and value {}"
     * This constructed message is placed inside a box.
     *
     * @param format SLF4J style format string
     * @param args   The values for any placeholders in the message format
     * @return A formatted message in a box.
     */
    public static String inBox(final String format, final Object... args) {
        return inBox(format, false, args);
    }

    private static String inBox(final String format,
                                final boolean addNewLine,
                                final Object... args) {
        if (format == null || format.isBlank()) {
            return "";
        } else {
            final String contentText = message(format, args);

            final int maxLineLen = contentText.lines()
                    .mapToInt(String::length)
                    .max()
                    .orElse(0);

            final String horizontalLine = Strings.repeat(String.valueOf(BOX_HORIZONTAL_LINE), maxLineLen + 4);
            final String horizontalSeparator = Strings.repeat(String.valueOf(BOX_HORIZONTAL_LINE), maxLineLen);
            final StringBuilder stringBuilder = new StringBuilder();
            if (addNewLine) {
                stringBuilder.append("\n");
            }
            // Top line
            stringBuilder
                    .append(BOX_TOP_LEFT)
                    .append(horizontalLine)
                    .append(BOX_TOP_RIGHT)
                    .append("\n");

            // Content
            contentText.lines()
                    .map(line -> {
                        // Add a pattern replacement to insert a horizontal rule like markdown.
                        if (line.equals("---")) {
                            return horizontalSeparator;
                        } else {
                            // Pad lines out to all the same length
                            final String variablePadding = Strings.repeat(" ", maxLineLen - line.length());
                            return line + variablePadding;
                        }
                    })
                    .forEach(linePlusPadding ->
                            stringBuilder
                                    .append(BOX_VERTICAL_LINE)
                                    .append("  ")
                                    .append(linePlusPadding)
                                    .append("  ")
                                    .append(BOX_VERTICAL_LINE)
                                    .append("\n"));

            // Bottom line
            stringBuilder
                    .append(BOX_BTM_LEFT)
                    .append(horizontalLine)
                    .append(BOX_BTM_RIGHT);

            return stringBuilder.toString();
        }
    }

    public static String getDurationMessage(final String work, final Duration duration) {
        return LogUtil.message("Completed [{}] in {}",
                work,
                duration);
    }

    /**
     * @return epochMs as an instant or null if epochMs is null.
     */
    public static Instant instant(final Long epochMs) {
        if (epochMs == null) {
            return null;
        } else {
            return Instant.ofEpochMilli(epochMs);
        }
    }

    /**
     * Output the value with its percentage of {@code total}, e.g. {@code 1000 (10%)}.
     * Supports numbers and {@link Duration} and {@link DurationAdder}.
     */
    public static <T> String withPercentage(final T value, final T total) {
        return withPercentage(value, value, total);
    }

    private static <T> String withPercentage(final Object originalValue,
                                             final T value,
                                             final T total) {
        if (value == null || total == null) {
            return null;
        } else {
            if (value instanceof Duration) {
                return withPercentage(value,
                        ((Duration) value).toMillis(),
                        ((Duration) total).toMillis());
            } else if (value instanceof DurationAdder) {
                return withPercentage(value,
                        ((DurationAdder) value).toMillis(),
                        ((DurationAdder) total).toMillis());
            } else if (value instanceof Number) {
                final double valNum = ((Number) value).doubleValue();
                final double totalNum = ((Number) total).doubleValue();
                if (totalNum == 0) {
                    return originalValue + " (undefined%)";
                } else {
                    final int pct = (int) (valNum / totalNum * 100);
                    return originalValue + " (" + pct + "%)";
                }
            } else {
                throw new IllegalArgumentException("Type "
                        + value.getClass().getSimpleName()
                        + " not supported");
            }
        }
    }
}
