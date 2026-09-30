package de.bsommerfeld.tinyrss.markup;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDecodingTest {

    private static final String XML = "<?xml version=\"1.0\"?><t>Börse – 5 €</t>";

    @Test
    void byteOrderMarksSettleItAndAreDropped() {
        // Anadolu, Der Standard, Kommersant, the Fed ship a UTF-8 BOM (measured 2026-09-30)
        byte[] utf8 = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, XML.getBytes(StandardCharsets.UTF_8));
        assertEquals(XML, TextDecoding.decode(utf8, "text/xml; charset=ISO-8859-1"));

        byte[] utf16 = concat(new byte[] {(byte) 0xFF, (byte) 0xFE}, XML.getBytes(StandardCharsets.UTF_16LE));
        assertEquals(XML, TextDecoding.decode(utf16, null));
    }

    @Test
    void validUtf8WinsOverADeclaredLatin1() {
        String declared = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>Börse</t>";
        assertEquals(declared, TextDecoding.decode(declared.getBytes(StandardCharsets.UTF_8), "text/xml; charset=iso-8859-1"));
    }

    @Test
    void aDeclaredCharsetIsBelievedWhenTheBytesAreNotUtf8() {
        String latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>Börse – 5 €</t>";
        assertEquals(latin, TextDecoding.decode(latin.getBytes(Charset.forName("windows-1252")), null),
                "ISO-8859-1 read as Windows-1252, as browsers do - the dash and the euro survive");

        String cyrillic = "<t>Биржа</t>";
        assertEquals(cyrillic, TextDecoding.decode(cyrillic.getBytes(Charset.forName("windows-1251")),
                "application/rss+xml; charset=windows-1251"));
    }

    @Test
    void strayBytesInUtf8CostOnlyThemselves() {
        byte[] mixed = concat(concat("<t>Börse ".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xE4, (byte) 0x96}),
                " Ölpreis</t>".getBytes(StandardCharsets.UTF_8));
        assertEquals("<t>Börse ä– Ölpreis</t>", TextDecoding.decode(mixed, "text/xml; charset=utf-8"));
    }

    @Test
    void utf8Validation() {
        assertTrue(TextDecoding.isValidUtf8("plain ascii".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(TextDecoding.isValidUtf8("😀 ö".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, TextDecoding.isValidUtf8(new byte[] {(byte) 0xC0, (byte) 0x80}), "overlong");
        assertEquals(false, TextDecoding.isValidUtf8(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}), "surrogate");
        assertEquals(false, TextDecoding.isValidUtf8(new byte[] {(byte) 0xE2, (byte) 0x82}), "cut short");
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }
}
