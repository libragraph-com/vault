package com.libragraph.vault.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BlobRefTest {

    private static final String VALID_HEX = "0123456789abcdef0123456789abcdef";
    private static final ContentHash HASH = ContentHash.fromHex(VALID_HEX);

    // --- Factory methods ---

    @Test
    void leafSetsIsContainerFalse() {
        BlobRef ref = BlobRef.leaf(HASH, 1024);
        assertThat(ref.isContainer()).isFalse();
        assertThat(ref.hash()).isEqualTo(HASH);
        assertThat(ref.leafSize()).isEqualTo(1024);
    }

    @Test
    void containerSetsIsContainerTrue() {
        BlobRef ref = BlobRef.container(HASH, 2048);
        assertThat(ref.isContainer()).isTrue();
        assertThat(ref.hash()).isEqualTo(HASH);
        assertThat(ref.leafSize()).isEqualTo(2048);
    }

    // --- Validation ---

    @Test
    void rejectsNullHash() {
        assertThatNullPointerException()
                .isThrownBy(() -> new BlobRef(null, 1024, false));
    }

    @Test
    void rejectsZeroSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlobRef(HASH, 0, false))
                .withMessageContaining("> 0");
    }

    @Test
    void rejectsNegativeSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlobRef(HASH, -1, false))
                .withMessageContaining("> 0");
    }

    // --- toString ---

    @Test
    void toStringLeaf() {
        BlobRef ref = BlobRef.leaf(HASH, 1048576);
        assertThat(ref.toString()).isEqualTo(VALID_HEX + "-1048576");
    }

    @Test
    void toStringContainer() {
        BlobRef ref = BlobRef.container(HASH, 524288);
        assertThat(ref.toString()).isEqualTo(VALID_HEX + "-524288_");
    }

    // --- parse ---

    @Test
    void parseRoundTripsLeaf() {
        BlobRef original = BlobRef.leaf(HASH, 1048576);
        BlobRef parsed = BlobRef.parse(original.toString());
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void parseRoundTripsContainer() {
        BlobRef original = BlobRef.container(HASH, 524288);
        BlobRef parsed = BlobRef.parse(original.toString());
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void parseRejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> BlobRef.parse(null));
    }

    @Test
    void parseRejectsMissingSeparator() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BlobRef.parse("nohyphen"))
                .withMessageContaining("no '-' separator");
    }

    @Test
    void parseRejectsBadHash() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BlobRef.parse("shorthex-1024"));
    }

    @Test
    void parseRejectsBadSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BlobRef.parse(VALID_HEX + "-notanumber"));
    }

    @Test
    void parseRejectsZeroSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BlobRef.parse(VALID_HEX + "-0"))
                .withMessageContaining("> 0");
    }

    @Test
    void parseRejectsNegativeSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BlobRef.parse(VALID_HEX + "--5"))
                .withMessageContaining("> 0");
    }
}
