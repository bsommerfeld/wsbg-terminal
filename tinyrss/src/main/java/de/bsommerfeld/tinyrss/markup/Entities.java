package de.bsommerfeld.tinyrss.markup;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Character references: XML's five, numeric ones, and HTML's named entities.
 * Feeds are XML, but they are written by HTML templates - {@code &nbsp;},
 * {@code &auml;} and {@code &euro;} turn up undeclared all the time, and an
 * XML parser gives up on the whole document at the first of them.
 */
public final class Entities {

    /** HTML 4's Latin-1 set, U+00A0 to U+00FF in order. */
    private static final String[] LATIN_1 = (
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr "
                    + "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest "
                    + "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml "
                    + "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig "
                    + "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml "
                    + "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml")
            .split(" ");

    /** HTML 4's symbol and special sets, and the punctuation names HTML 5 added that feeds use. */
    private static final String OTHERS = """
            quot 34 amp 38 apos 39 lt 60 gt 62
            OElig 338 oelig 339 Scaron 352 scaron 353 Yuml 376 fnof 402 circ 710 tilde 732
            Alpha 913 Beta 914 Gamma 915 Delta 916 Epsilon 917 Zeta 918 Eta 919 Theta 920 Iota 921 Kappa 922
            Lambda 923 Mu 924 Nu 925 Xi 926 Omicron 927 Pi 928 Rho 929 Sigma 931 Tau 932 Upsilon 933 Phi 934
            Chi 935 Psi 936 Omega 937
            alpha 945 beta 946 gamma 947 delta 948 epsilon 949 zeta 950 eta 951 theta 952 iota 953 kappa 954
            lambda 955 mu 956 nu 957 xi 958 omicron 959 pi 960 rho 961 sigmaf 962 sigma 963 tau 964 upsilon 965
            phi 966 chi 967 psi 968 omega 969 thetasym 977 upsih 978 piv 982
            ensp 8194 emsp 8195 thinsp 8201 zwnj 8204 zwj 8205 lrm 8206 rlm 8207 ndash 8211 mdash 8212
            lsquo 8216 rsquo 8217 sbquo 8218 ldquo 8220 rdquo 8221 bdquo 8222 dagger 8224 Dagger 8225
            bull 8226 hellip 8230 permil 8240 prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250 oline 8254
            frasl 8260 euro 8364 image 8465 weierp 8472 real 8476 trade 8482 alefsym 8501
            larr 8592 uarr 8593 rarr 8594 darr 8595 harr 8596 crarr 8629 lArr 8656 uArr 8657 rArr 8658
            dArr 8659 hArr 8660 forall 8704 part 8706 exist 8707 empty 8709 nabla 8711 isin 8712 notin 8713
            ni 8715 prod 8719 sum 8721 minus 8722 lowast 8727 radic 8730 prop 8733 infin 8734 ang 8736
            and 8743 or 8744 cap 8745 cup 8746 int 8747 there4 8756 sim 8764 cong 8773 asymp 8776 ne 8800
            equiv 8801 le 8804 ge 8805 sub 8834 sup 8835 nsub 8836 sube 8838 supe 8839 oplus 8853
            otimes 8855 perp 8869 sdot 8901 lceil 8968 rceil 8969 lfloor 8970 rfloor 8971 lang 9001
            rang 9002 loz 9674 spades 9824 clubs 9827 hearts 9829 diams 9830
            Tab 9 NewLine 10 excl 33 num 35 dollar 36 percnt 37 lpar 40 rpar 41 ast 42 plus 43 comma 44
            period 46 sol 47 colon 58 semi 59 equals 61 quest 63 commat 64 lsqb 91 bsol 92 rsqb 93 Hat 94
            lowbar 95 grave 96 lcub 123 verbar 124 vert 124 rcub 125 hyphen 8208
            """;

    private static final Map<String, Integer> NAMED = named();

    private Entities() {
    }

    /**
     * The code point of a named entity, {@code null} when unknown. Exact case
     * first ({@code Auml} is not {@code auml}), then lower case for the
     * shouting spellings ({@code &NBSP;}).
     */
    public static Integer named(String name) {
        Integer codePoint = NAMED.get(name);
        return codePoint != null ? codePoint : NAMED.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Replaces every character reference in {@code text}. Unknown names and
     * a bare {@code &} stay as they are.
     */
    public static String decode(String text) {
        int amp = text.indexOf('&');
        if (amp < 0) {
            return text;
        }
        StringBuilder decoded = new StringBuilder(text.length());
        decoded.append(text, 0, amp);
        int i = amp;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&') {
                decoded.append(c);
                i++;
                continue;
            }
            int end = reference(text, i, decoded);
            if (end < 0) {
                decoded.append('&');
                i++;
            } else {
                i = end;
            }
        }
        return decoded.toString();
    }

    /**
     * Decodes the reference starting at {@code text[start] == '&'} into
     * {@code out}.
     *
     * @return the index after the reference, or {@code -1} when there is none
     */
    static int reference(String text, int start, StringBuilder out) {
        int i = start + 1;
        if (i < text.length() && text.charAt(i) == '#') {
            return numeric(text, i + 1, out);
        }
        int nameEnd = i;
        while (nameEnd < text.length() && nameEnd - i < 32 && Character.isLetterOrDigit(text.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == i || nameEnd >= text.length() || text.charAt(nameEnd) != ';') {
            return -1;
        }
        Integer codePoint = named(text.substring(i, nameEnd));
        if (codePoint == null) {
            return -1;
        }
        out.appendCodePoint(codePoint);
        return nameEnd + 1;
    }

    /** {@code &#228;} or {@code &#xE4;}; the {@code ;} may be missing, as HTML allows. */
    private static int numeric(String text, int from, StringBuilder out) {
        boolean hex = from < text.length() && (text.charAt(from) == 'x' || text.charAt(from) == 'X');
        int digitsStart = hex ? from + 1 : from;
        int end = digitsStart;
        while (end < text.length() && end - digitsStart < 8
                && Character.digit(text.charAt(end), hex ? 16 : 10) >= 0) {
            end++;
        }
        if (end == digitsStart) {
            return -1;
        }
        int codePoint = Integer.parseInt(text.substring(digitsStart, end), hex ? 16 : 10);
        if (end < text.length() && text.charAt(end) == ';') {
            end++;
        }
        out.appendCodePoint(isAllowed(codePoint) ? windows1252(codePoint) : 0xFFFD);
        return end;
    }

    private static boolean isAllowed(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || codePoint >= 0x20 && codePoint <= 0x10FFFF && (codePoint < 0xD800 || codePoint > 0xDFFF);
    }

    /**
     * {@code &#150;} means the en dash: templates write Windows-1252 bytes as
     * references, and browsers read the C1 range that way.
     */
    private static int windows1252(int codePoint) {
        if (codePoint < 0x80 || codePoint > 0x9F) {
            return codePoint;
        }
        char mapped = TextDecoding.WINDOWS_1252_C1.charAt(codePoint - 0x80);
        return mapped == '�' ? codePoint : mapped;
    }

    private static Map<String, Integer> named() {
        Map<String, Integer> named = new HashMap<>();
        for (int i = 0; i < LATIN_1.length; i++) {
            named.put(LATIN_1[i], 0xA0 + i);
        }
        String[] pairs = OTHERS.trim().split("\\s+");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            named.put(pairs[i], Integer.parseInt(pairs[i + 1]));
        }
        return Map.copyOf(named);
    }
}
