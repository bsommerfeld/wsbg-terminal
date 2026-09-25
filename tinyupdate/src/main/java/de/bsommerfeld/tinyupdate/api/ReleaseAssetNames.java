package de.bsommerfeld.tinyupdate.api;

import java.util.StringJoiner;

/**
 * The names of the release assets one update stream reads: its manifest, the
 * full archive, and the app/deps split of it.
 *
 * <p>
 * The naming rule is shared with the packaging action ({@code action.yml}) and
 * must stay identical on both sides: the non-empty parts of
 * {@code prefix}, {@code platform} and the base name joined by {@code -}. The
 * app archive carries no platform - it holds the application's own jars and
 * scripts, which are the same everywhere, so every platform stream shares it.
 *
 * <pre>
 * of("", "")                      update.json          files.zip                  app.zip       deps.zip
 * of("wsbg", "windows-x86_64")    wsbg-windows-x86_64-update.json   ...-files.zip   wsbg-app.zip  wsbg-windows-x86_64-deps.zip
 * </pre>
 *
 * @param manifest    the {@code update.json} of the stream
 * @param archive     the archive holding every file of the manifest
 * @param appArchive  the application half of the split, or {@code null} for no split
 * @param depsArchive the dependency half of the split, or {@code null} for no split
 */
public record ReleaseAssetNames(String manifest, String archive, String appArchive, String depsArchive) {

    /** The unprefixed, platform-neutral stream: {@code update.json}, {@code files.zip}, {@code app.zip}, {@code deps.zip}. */
    public static final ReleaseAssetNames DEFAULT = of("", "");

    /**
     * The asset names of a stream.
     *
     * @param prefix   what sets this application's assets apart from others in
     *                 the same release, or empty
     * @param platform the platform the stream serves ({@link Platform#current()}),
     *                 or empty for a platform-neutral stream
     */
    public static ReleaseAssetNames of(String prefix, String platform) {
        return new ReleaseAssetNames(
                join(prefix, platform, "update.json"),
                join(prefix, platform, "files.zip"),
                join(prefix, "", "app.zip"),
                join(prefix, platform, "deps.zip"));
    }

    private static String join(String prefix, String platform, String base) {
        StringJoiner name = new StringJoiner("-");
        if (prefix != null && !prefix.isBlank()) name.add(prefix);
        if (platform != null && !platform.isBlank()) name.add(platform);
        return name.add(base).toString();
    }
}
