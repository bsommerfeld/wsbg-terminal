package de.bsommerfeld.tinyfetch.curl;

/**
 * The numeric curl constants TinyFetch uses, copied from {@code curl/curl.h}
 * of libcurl-impersonate. An option's number is its type base plus its index:
 * {@code LONG} 0, {@code OBJECTPOINT} 10000, {@code FUNCTIONPOINT} 20000,
 * {@code OFF_T} 30000.
 */
final class CurlConstants {

    private CurlConstants() {
    }

    // ---- CURLoption -------------------------------------------------------

    static final int OPT_WRITEDATA = 10_001;
    static final int OPT_URL = 10_002;
    static final int OPT_ERRORBUFFER = 10_010;
    static final int OPT_WRITEFUNCTION = 20_011;
    static final int OPT_HTTPHEADER = 10_023;
    static final int OPT_HEADERDATA = 10_029;
    static final int OPT_COOKIEFILE = 10_031;
    static final int OPT_CUSTOMREQUEST = 10_036;
    static final int OPT_NOPROGRESS = 43;
    static final int OPT_POST = 47;
    static final int OPT_FOLLOWLOCATION = 52;
    static final int OPT_XFERINFODATA = 10_057;
    static final int OPT_CAINFO = 10_065;
    static final int OPT_MAXREDIRS = 68;
    static final int OPT_HEADERFUNCTION = 20_079;
    static final int OPT_HTTPGET = 80;
    static final int OPT_NOSIGNAL = 99;
    static final int OPT_SHARE = 10_100;
    static final int OPT_ACCEPT_ENCODING = 10_102;
    static final int OPT_POSTFIELDSIZE_LARGE = 30_120;
    static final int OPT_COOKIELIST = 10_135;
    static final int OPT_TIMEOUT_MS = 155;
    static final int OPT_CONNECTTIMEOUT_MS = 156;
    static final int OPT_COPYPOSTFIELDS = 10_165;
    static final int OPT_XFERINFOFUNCTION = 20_219;
    static final int OPT_PROTOCOLS_STR = 10_318;
    static final int OPT_REDIR_PROTOCOLS_STR = 10_319;

    // ---- CURLINFO ---------------------------------------------------------

    static final int INFO_EFFECTIVE_URL = 0x100000 + 1;
    static final int INFO_RESPONSE_CODE = 0x200000 + 2;
    static final int INFO_COOKIELIST = 0x400000 + 28;
    static final int INFO_HTTP_VERSION = 0x200000 + 46;

    // ---- share interface --------------------------------------------------

    static final int SHOPT_SHARE = 1;
    static final int SHOPT_LOCKFUNC = 3;
    static final int SHOPT_UNLOCKFUNC = 4;
    static final int SHOPT_USERDATA = 5;

    static final int LOCK_DATA_COOKIE = 2;
    static final int LOCK_DATA_DNS = 3;
    static final int LOCK_DATA_SSL_SESSION = 4;
    /** {@code CURL_LOCK_DATA_LAST}: one past the highest lock id. */
    static final int LOCK_DATA_COUNT = 8;

    // ---- misc -------------------------------------------------------------

    /** {@code CURL_ERROR_SIZE} */
    static final int ERROR_SIZE = 256;

    /** {@code CURL_HTTP_VERSION_*} as reported by {@code CURLINFO_HTTP_VERSION}. */
    static String httpVersionName(long version) {
        return switch ((int) version) {
            case 1 -> "HTTP/1.0";
            case 2 -> "HTTP/1.1";
            case 3 -> "HTTP/2";
            case 30 -> "HTTP/3";
            default -> "HTTP";
        };
    }
}
