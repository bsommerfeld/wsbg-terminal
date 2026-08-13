package de.bsommerfeld.wsbg.terminal.ui.debug;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logback appender that mirrors every root-logger event into {@link DebugLog}
 * — structured (its own field extraction, never the ANSI console pattern),
 * bounded (the ring), and DEV-ONLY: it is registered programmatically by
 * {@link #install()}, which {@code AppMain} calls exclusively behind
 * {@code if (Debug.ENABLED)}. It is deliberately NOT in {@code logback.xml},
 * so a shipped build never even constructs it.
 *
 * <p>Cost per event in dev: one record allocation plus one ring append
 * (leaf-locked). {@code AppenderBase} serialises {@code doAppend} per
 * appender instance; the ring lock nests inside that and calls nothing out,
 * so no new lock-order edge exists.
 */
public final class DebugLogAppender extends AppenderBase<ILoggingEvent> {

    /** Attaches one instance to the root logger of the default context. Idempotent. */
    public static synchronized void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return; // foreign slf4j backend — nothing to attach to
        }
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(NAME) != null) return;
        DebugLogAppender appender = new DebugLogAppender();
        appender.setContext(context);
        appender.setName(NAME);
        appender.start();
        root.addAppender(appender);
    }

    static final String NAME = "WSBG-DEBUG-RING";

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message != null && message.length() > DebugLog.MESSAGE_MAX) {
            message = message.substring(0, DebugLog.MESSAGE_MAX) + "…";
        }
        DebugLog.add(new DebugLog.Line(
                event.getTimeStamp(),
                event.getLevel() == null ? "" : event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                message == null ? "" : message,
                summarize(event.getThrowableProxy())));
    }

    /** One line: exception class + message; the ring is a window, not a stack archive. */
    private static String summarize(IThrowableProxy proxy) {
        if (proxy == null) return null;
        String msg = proxy.getMessage();
        return msg == null ? proxy.getClassName() : proxy.getClassName() + ": " + msg;
    }
}
