package de.bsommerfeld.wsbg.terminal.ui.debug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLogAppenderTest {

    private static final String LOGGER_NAME = "wsbg.debuglog.test";

    @BeforeEach
    void freshRing() {
        DebugLogAppender.install(); // idempotent
        DebugLog.reset();
    }

    private static List<DebugLog.Line> mine() {
        return DebugLog.recent(Integer.MAX_VALUE).stream()
                .filter(l -> LOGGER_NAME.equals(l.logger())).toList();
    }

    @Test
    void capturesFormattedLinesWithLevelAndThread() {
        Logger log = LoggerFactory.getLogger(LOGGER_NAME);
        log.warn("basin at {} of {}", 8700, 25000);
        List<DebugLog.Line> lines = mine();
        assertEquals(1, lines.size());
        DebugLog.Line line = lines.get(0);
        assertEquals("WARN", line.level());
        assertEquals("basin at 8700 of 25000", line.message());
        assertEquals(Thread.currentThread().getName(), line.thread());
        assertTrue(line.atMs() > 0);
    }

    @Test
    void summarisesThrowablesToOneLine() {
        LoggerFactory.getLogger(LOGGER_NAME)
                .error("digest failed", new IllegalStateException("wall"));
        DebugLog.Line line = mine().get(0);
        assertNotNull(line.error());
        assertEquals("java.lang.IllegalStateException: wall", line.error());
    }

    @Test
    void capsOverlongMessages() {
        LoggerFactory.getLogger(LOGGER_NAME).info("x".repeat(2_000));
        DebugLog.Line line = mine().get(0);
        assertEquals(DebugLog.MESSAGE_MAX + 1, line.message().length()); // 400 + ellipsis
        assertTrue(line.message().endsWith("…"));
    }

    @Test
    void aggregateCountsWarnAndErrorPerLogger() {
        Logger log = LoggerFactory.getLogger(LOGGER_NAME);
        log.warn("one");
        log.warn("two");
        log.error("three");
        log.info("not counted");
        DebugLog.Aggregate aggregate = DebugLog.aggregate();
        assertTrue(aggregate.warnCount() >= 2);
        assertTrue(aggregate.errorCount() >= 1);
        assertEquals(3, aggregate.warnAndErrorByLogger().get(LOGGER_NAME));
    }

    @Test
    void installIsIdempotent() {
        DebugLogAppender.install();
        DebugLogAppender.install();
        LoggerFactory.getLogger(LOGGER_NAME).warn("once");
        assertEquals(1, mine().size(), "a double install must not duplicate lines");
    }
}
