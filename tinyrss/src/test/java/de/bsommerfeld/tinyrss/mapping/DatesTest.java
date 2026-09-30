package de.bsommerfeld.tinyrss.mapping;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // RFC 822 as the spec wants it, and as feeds write it
            "Wed, 30 Sep 2026 10:15:00 +0200          | 2026-09-30T08:15:00Z",
            "Wed, 30 Sep 2026 08:15:00 GMT            | 2026-09-30T08:15:00Z",
            "30 Sep 2026 08:15:00 Z                   | 2026-09-30T08:15:00Z",
            "Wed,30 Sep 2026 10:15 +02:00             | 2026-09-30T08:15:00Z",
            "Wed, 30 Sep 26 04:15:00 EDT              | 2026-09-30T08:15:00Z",
            "Wed, 30 September 2026 10:15:00 CEST     | 2026-09-30T08:15:00Z",
            "Mi, 30 Sep 2026 10:15:00 MESZ            | 2026-09-30T08:15:00Z",
            "Mi, 30 Okt 2026 09:15:00 MEZ             | 2026-10-30T08:15:00Z",
            "Mo, 2 Mär 2026 09:15:00 +0100            | 2026-03-02T08:15:00Z",
            "mar., 30 sept. 2026 10:15:00 +0200       | 2026-09-30T08:15:00Z",
            "30 déc. 2026 08:15:00 GMT                | 2026-12-30T08:15:00Z",
            "Wed, 30 Sep 2026 10:15:00 +0200 (CEST)   | 2026-09-30T08:15:00Z",
            "Wed, 30 Sep 2026 10:15:00 GMT+2          | 2026-09-30T08:15:00Z",
            "Wed, 30 Sep 2026 08:15:00                | 2026-09-30T08:15:00Z",
            "Sep 30, 2026 8:15 AM                     | 2026-09-30T08:15:00Z",
            "Sep 30, 2026 8:15 PM                     | 2026-09-30T20:15:00Z",
            // ISO 8601 / RFC 3339
            "2026-09-30T08:15:00Z                     | 2026-09-30T08:15:00Z",
            "2026-09-30T10:15:00.123+02:00            | 2026-09-30T08:15:00.123Z",
            "2026-09-30T10:15:00+0200                 | 2026-09-30T08:15:00Z",
            "2026-09-30T10:15+02                      | 2026-09-30T08:15:00Z",
            "2026-09-30 08:15:00                      | 2026-09-30T08:15:00Z",
            "2026-09-30t08:15:00z                     | 2026-09-30T08:15:00Z",
            "2026-09-30                               | 2026-09-30T00:00:00Z",
            // German, epoch
            "30.09.2026 08:15                         | 2026-09-30T08:15:00Z",
            "1790756100                               | 2026-09-30T08:15:00Z",
            "1790756100000                            | 2026-09-30T08:15:00Z"})
    void parses(String raw, String expected) {
        assertEquals(Instant.parse(expected), Dates.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "gestern", "31 Feb 2026 10:00:00 GMT", "2026-13-01", "Sep 2026"})
    void noDate(String raw) {
        assertNull(Dates.parse(raw));
    }
}
