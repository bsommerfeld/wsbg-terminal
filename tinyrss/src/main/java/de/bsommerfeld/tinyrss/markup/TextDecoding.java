package de.bsommerfeld.tinyrss.markup;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bytes to text, when neither the server nor the document can be trusted to
 * name the charset.
 *
 * <ol>
 *   <li>A byte-order mark settles it - and is dropped, it is not text.</li>
 *   <li>Bytes that are valid UTF-8 are read as UTF-8, whatever is declared:
 *       text in any other charset is practically never valid UTF-8 once it
 *       holds a single non-ASCII character, while a template that declares
 *       {@code ISO-8859-1} over UTF-8 content is common.</li>
 *   <li>Otherwise a declared charset other than UTF-8 is believed - the XML
 *       declaration first, then the {@code content-type} header.</li>
 *   <li>Otherwise it is UTF-8 with stray bytes: each byte that is no part of
 *       a valid sequence is read as Windows-1252, so one Latin-1 umlaut from
 *       a pasted snippet does not garble the rest of the feed.</li>
 * </ol>
 */
public final class TextDecoding {

    /** Windows-1252 for {@code 0x80..0x9F}; U+FFFD where it defines nothing. */
    static final String WINDOWS_1252_C1 =
            "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD"
                    + "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178";

    private static final Pattern XML_ENCODING = Pattern.compile(
            "^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']");
    private static final Pattern HTTP_CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)");
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)", Pattern.CASE_INSENSITIVE);

    private TextDecoding() {
    }

    /**
     * @param contentType the {@code content-type} header, or {@code null}
     */
    public static String decode(byte[] bytes, String contentType) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00) || startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) {
            Charset utf32 = supported(bytes[0] == 0 ? "UTF-32BE" : "UTF-32LE");
            if (utf32 != null) {
                return new String(bytes, 4, bytes.length - 4, utf32);
            }
        }
        if (startsWith(bytes, 0xFE, 0xFF) || startsWith(bytes, 0x3C, 0x00, 0x3F, 0x00) && bytes.length % 2 == 0) {
            boolean bom = bytes[0] != 0x3C;
            Charset utf16 = bom ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
            return new String(bytes, bom ? 2 : 0, bytes.length - (bom ? 2 : 0), utf16);
        }
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0x00, 0x3C, 0x00, 0x3F)) {
            boolean bom = bytes[0] != 0x00;
            Charset utf16 = bom ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
            return new String(bytes, bom ? 2 : 0, bytes.length - (bom ? 2 : 0), utf16);
        }
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        Charset declared = declared(bytes, contentType);
        if (declared != null && !declared.equals(StandardCharsets.UTF_8)) {
            return new String(bytes, declared);
        }
        return utf8WithStrayBytes(bytes);
    }

    /** Whether {@code bytes} are UTF-8 through and through, ASCII included. */
    static boolean isValidUtf8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            int length = sequenceLength(bytes, i);
            if (length == 0) {
                return false;
            }
            i += length;
        }
        return true;
    }

    /** UTF-8, and every byte outside a valid sequence as Windows-1252. */
    static String utf8WithStrayBytes(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int length = sequenceLength(bytes, i);
            if (length == 0) {
                int stray = bytes[i] & 0xFF;
                text.append(stray >= 0x80 && stray <= 0x9F ? WINDOWS_1252_C1.charAt(stray - 0x80) : (char) stray);
                i++;
            } else {
                text.append(new String(bytes, i, length, StandardCharsets.UTF_8));
                i += length;
            }
        }
        return text.toString();
    }

    /** The length of the valid UTF-8 sequence at {@code i}, {@code 0} when there is none. */
    private static int sequenceLength(byte[] bytes, int i) {
        int lead = bytes[i] & 0xFF;
        if (lead < 0x80) {
            return 1;
        }
        int length;
        int secondMin = 0x80;
        int secondMax = 0xBF;
        if (lead >= 0xC2 && lead <= 0xDF) {
            length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            length = 3;
            if (lead == 0xE0) {
                secondMin = 0xA0;
            } else if (lead == 0xED) {
                secondMax = 0x9F;
            }
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            length = 4;
            if (lead == 0xF0) {
                secondMin = 0x90;
            } else if (lead == 0xF4) {
                secondMax = 0x8F;
            }
        } else {
            return 0;
        }
        if (i + length > bytes.length) {
            return 0;
        }
        int second = bytes[i + 1] & 0xFF;
        if (second < secondMin || second > secondMax) {
            return 0;
        }
        for (int k = 2; k < length; k++) {
            int next = bytes[i + k] & 0xFF;
            if (next < 0x80 || next > 0xBF) {
                return 0;
            }
        }
        return length;
    }

    /** The charset the XML declaration, the header or an HTML {@code <meta>} names, if the JDK knows it. */
    private static Charset declared(byte[] bytes, String contentType) {
        String head = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        Matcher xml = XML_ENCODING.matcher(head);
        if (xml.find() && supported(xml.group(1)) != null) {
            return supported(xml.group(1));
        }
        if (contentType != null) {
            Matcher http = HTTP_CHARSET.matcher(contentType);
            if (http.find() && supported(http.group(1)) != null) {
                return supported(http.group(1));
            }
        }
        Matcher meta = META_CHARSET.matcher(head);
        return meta.find() ? supported(meta.group(1)) : null;
    }

    /**
     * The charset of a name, {@code null} when unknown. {@code ISO-8859-1}
     * becomes Windows-1252, as in every browser: the templates that declare
     * it write curly quotes and the euro sign from Windows-1252.
     */
    private static Charset supported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("iso-8859-1") || lower.equals("latin1") || lower.equals("us-ascii")) {
            lower = "windows-1252";
        }
        try {
            return Charset.forName(lower);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
