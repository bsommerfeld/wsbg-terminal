/**
 * TinyUpdate: keeps an installed application in step with its GitHub releases,
 * file by file, one stream per platform. The packaging side is the GitHub
 * action next to this module ({@code tinyupdate/action}).
 */
module de.bsommerfeld.tinyupdate {
    requires java.net.http;

    exports de.bsommerfeld.tinyupdate.api;
    exports de.bsommerfeld.tinyupdate.download;
    exports de.bsommerfeld.tinyupdate.handoff;
    exports de.bsommerfeld.tinyupdate.hash;
    exports de.bsommerfeld.tinyupdate.json;
    exports de.bsommerfeld.tinyupdate.model;
    exports de.bsommerfeld.tinyupdate.update;
}
