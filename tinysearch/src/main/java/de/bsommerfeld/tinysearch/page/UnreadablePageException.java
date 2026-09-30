package de.bsommerfeld.tinysearch.page;

/** An engine's answer that is not its result page - neither hits nor its "nothing found". */
public final class UnreadablePageException extends Exception {

    public UnreadablePageException(String message) {
        super(message);
    }
}
