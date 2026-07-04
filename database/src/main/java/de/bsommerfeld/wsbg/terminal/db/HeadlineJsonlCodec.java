package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The JSONL persistence layer behind {@link HeadlineArchive}: one
 * {@link HeadlineRecord} per line, append-only. Pure file IO — no index, no
 * query state — so it is unit-testable against a temp file with no query
 * concerns.
 *
 * <p><b>Torn-line tolerance:</b> {@link #readAll()} skips any line that fails to
 * parse (a torn final line from a crash, or a hand-edited line), losing at most
 * that one record instead of aborting the whole load.
 *
 * <p>This codec is deliberately NOT synchronized — {@link HeadlineArchive} holds
 * one monitor over both this codec and the {@link HeadlineIndex} so an append's
 * index-mutation and file-write stay atomic against a concurrent clear/read.
 */
final class HeadlineJsonlCodec {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineJsonlCodec.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    HeadlineJsonlCodec(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /** Records read from the file, plus how many lines were skipped as broken. */
    record LoadResult(List<HeadlineRecord> records, int broken) {
    }

    /**
     * Reads every record from the file, tolerating a torn/broken line by
     * skipping it. Returns an empty result if the file is absent or unreadable.
     */
    LoadResult readAll() {
        if (!Files.exists(file)) return new LoadResult(List.of(), 0);
        List<HeadlineRecord> out = new ArrayList<>();
        int broken = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    out.add(mapper.readValue(line, HeadlineRecord.class));
                } catch (Exception e) {
                    broken++; // torn tail from a crash, or a hand-edited line — skip it
                }
            }
        } catch (IOException e) {
            LOG.warn("Headline archive unreadable ({}): {}", file, e.getMessage());
            return new LoadResult(List.of(), 0);
        }
        return new LoadResult(out, broken);
    }

    /** Atomically appends one serialized record line, creating the dir/file as needed. */
    void append(HeadlineRecord record) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // The in-memory index keeps the record for this session either way.
            LOG.warn("Failed to append headline to archive: {}", e.getMessage());
        }
    }

    /** Deletes the backing file if present. */
    void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Failed to delete archive file {}: {}", file, e.getMessage());
        }
    }
}
