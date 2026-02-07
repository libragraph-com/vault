package com.libragraph.vault.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ContentHashTest {

    @Test
    void shouldConstructFromValidBytes() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xAB;
        bytes[15] = (byte) 0xCD;

        ContentHash hash = new ContentHash(bytes);
        assertThat(hash.bytes()).hasSize(16);
        assertThat(hash.bytes()[0]).isEqualTo((byte) 0xAB);
    }

    @Test
    void shouldDefensiveCopyOnConstruction() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0x01;
        ContentHash hash = new ContentHash(bytes);

        // Mutate original — should not affect hash
        bytes[0] = (byte) 0xFF;
        assertThat(hash.bytes()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    void shouldRejectNullBytes() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ContentHash(null));
    }

    @Test
    void shouldRejectWrongLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentHash(new byte[8]))
                .withMessageContaining("16 bytes");
    }

    @Test
    void shouldRoundTripHex() {
        String hex = "0123456789abcdef0123456789abcdef";
        ContentHash hash = ContentHash.fromHex(hex);

        assertThat(hash.toHex()).isEqualTo(hex);
    }

    @Test
    void shouldRejectInvalidHexLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContentHash.fromHex("abcd"))
                .withMessageContaining("32 characters");
    }

    @Test
    void shouldRejectInvalidHexCharacters() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ContentHash.fromHex("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        ContentHash a = ContentHash.fromHex("0123456789abcdef0123456789abcdef");
        ContentHash b = ContentHash.fromHex("0123456789abcdef0123456789abcdef");
        ContentHash c = ContentHash.fromHex("fedcba9876543210fedcba9876543210");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void shouldReturnHexFromToString() {
        String hex = "0123456789abcdef0123456789abcdef";
        ContentHash hash = ContentHash.fromHex(hex);

        assertThat(hash.toString()).isEqualTo(hex);
    }
}
