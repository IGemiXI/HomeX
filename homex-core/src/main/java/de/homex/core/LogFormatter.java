package de.homex.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/** Formatiert Logzeilen wie bei Spigot: {@code [22:05:01 INFO] [DemoLicht] Nachricht} */
final class LogFormatter extends Formatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder()
                .append('[').append(LocalTime.now().format(TIME)).append(' ')
                .append(r.getLevel().getName()).append("] ");
        String name = r.getLoggerName();
        if (name != null && name.startsWith("HomeX.")) {
            sb.append('[').append(name.substring(6)).append("] ");
        }
        sb.append(formatMessage(r)).append(System.lineSeparator());
        if (r.getThrown() != null) {
            StringWriter sw = new StringWriter();
            r.getThrown().printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        return sb.toString();
    }
}
