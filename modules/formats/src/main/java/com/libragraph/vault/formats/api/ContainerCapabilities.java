package com.libragraph.vault.formats.api;

/**
 * Describes what a container format can preserve during reconstruction.
 * Maps to ADR-009 reconstruction tiers.
 */
public record ContainerCapabilities(
        ReconstructionTier tier,
        boolean preservesTimestamps,
        boolean preservesPermissions,
        boolean preservesOrder
) {
    public enum ReconstructionTier {
        /** Bit-identical reconstruction possible (ZIP, TAR, GZIP) */
        TIER_1_RECONSTRUCTABLE,

        /** Must store original container blob (7Z, RAR) */
        TIER_2_STORED,

        /** Contents only, format discarded (PST, MBOX) */
        TIER_3_CONTENTS_ONLY
    }

    public static ContainerCapabilities tier1() {
        return new ContainerCapabilities(
                ReconstructionTier.TIER_1_RECONSTRUCTABLE,
                true,
                true,
                true
        );
    }

    public static ContainerCapabilities tier2() {
        return new ContainerCapabilities(
                ReconstructionTier.TIER_2_STORED,
                false,
                false,
                false
        );
    }

    public static ContainerCapabilities tier3() {
        return new ContainerCapabilities(
                ReconstructionTier.TIER_3_CONTENTS_ONLY,
                false,
                false,
                false
        );
    }
}
