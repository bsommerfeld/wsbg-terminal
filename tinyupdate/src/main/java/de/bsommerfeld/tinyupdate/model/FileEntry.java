package de.bsommerfeld.tinyupdate.model;

/**
 * A single file tracked by the update manifest.
 *
 * @param path   relative path from the app root (e.g. "lib/core.jar")
 * @param sha256 hex-encoded SHA-256 checksum
 * @param size   file size in bytes
 */
public record FileEntry(String path, String sha256, long size) {}
