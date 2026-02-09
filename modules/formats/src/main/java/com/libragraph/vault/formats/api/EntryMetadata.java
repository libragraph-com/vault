package com.libragraph.vault.formats.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * POSIX/FS metadata for a container entry.
 * Container-specific: the same blob can have different metadata in different archives.
 * Nullable fields — only populated when the container format provides them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntryMetadata(
        Instant mtime,
        Instant ctime,
        Instant atime,
        Integer posixMode,
        Long uid,
        Long gid,
        String ownerName,
        String groupName,
        String linkTarget
) {
    /** Convenience: entry with only mtime. */
    public static EntryMetadata ofMtime(Instant mtime) {
        return new EntryMetadata(mtime, null, null, null, null, null, null, null, null);
    }
}
