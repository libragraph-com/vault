package com.libragraph.vault.formats.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DetectionCriteriaTest {

    @Test
    void shouldMatchMagicBytesAtOffsetZero() {
        byte[] zipMagic = {0x50, 0x4B, 0x03, 0x04};
        var criteria = new DetectionCriteria(
                Set.of("application/zip"), Set.of("zip"), zipMagic, 0, 200);

        byte[] header = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        assertThat(criteria.matches(null, null, header)).isTrue();
    }

    @Test
    void shouldMatchMagicBytesAtOffset257() {
        byte[] tarMagic = {'u', 's', 't', 'a', 'r'};
        var criteria = new DetectionCriteria(
                Set.of("application/x-tar"), Set.of("tar"), tarMagic, 257, 200);

        // Build a 512-byte header with "ustar" at offset 257
        byte[] header = new byte[512];
        System.arraycopy(tarMagic, 0, header, 257, tarMagic.length);

        assertThat(criteria.matches(null, null, header)).isTrue();
    }

    @Test
    void shouldNotMatchMagicIfHeaderTooShort() {
        byte[] tarMagic = {'u', 's', 't', 'a', 'r'};
        var criteria = new DetectionCriteria(
                Set.of("application/x-tar"), Set.of("tar"), tarMagic, 257, 200);

        byte[] shortHeader = new byte[100];
        assertThat(criteria.matches(null, null, shortHeader)).isFalse();
    }

    @Test
    void shouldMatchMimeType() {
        var criteria = new DetectionCriteria(
                Set.of("text/plain"), Set.of("txt"), null, 0, 100);

        assertThat(criteria.matches("text/plain", "file.dat", new byte[0])).isTrue();
    }

    @Test
    void shouldMatchMimeWildcard() {
        var criteria = new DetectionCriteria(
                Set.of("text/*"), Set.of("txt"), null, 0, 100);

        assertThat(criteria.matches("text/html", "file.dat", new byte[0])).isTrue();
    }

    @Test
    void shouldMatchExtension() {
        var criteria = new DetectionCriteria(
                Set.of("application/zip"), Set.of("zip"), null, 0, 200);

        assertThat(criteria.matches(null, "archive.zip", new byte[0])).isTrue();
    }

    @Test
    void shouldNotMatchUnrelatedFile() {
        var criteria = new DetectionCriteria(
                Set.of("application/zip"), Set.of("zip"),
                new byte[]{0x50, 0x4B, 0x03, 0x04}, 0, 200);

        assertThat(criteria.matches("text/plain", "file.txt", "Hello".getBytes())).isFalse();
    }

    @Test
    void shouldCatchAllMatch() {
        var criteria = DetectionCriteria.catchAll(0);

        assertThat(criteria.matches("anything/here", "file.xyz", new byte[0])).isTrue();
        assertThat(criteria.priority()).isEqualTo(0);
    }

    @Test
    void shouldPreservePriority() {
        var high = new DetectionCriteria(Set.of("a/b"), Set.of("x"), null, 0, 200);
        var low = new DetectionCriteria(Set.of("a/b"), Set.of("x"), null, 0, 50);

        assertThat(high.priority()).isGreaterThan(low.priority());
    }
}
