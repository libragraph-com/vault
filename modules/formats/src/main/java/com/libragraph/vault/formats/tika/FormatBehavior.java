package com.libragraph.vault.formats.tika;

/**
 * Describes behavior characteristics of a file format.
 * Used by FormatBehaviorRegistry to customize Universal Tika handler.
 */
public record FormatBehavior(
        boolean hasChildren,
        boolean isCompressible
) {
    /**
     * Creates a format behavior.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience method for simple leaf formats.
     */
    public static FormatBehavior leaf(boolean compressible) {
        return new FormatBehavior(false, compressible);
    }

    /**
     * Convenience method for container formats.
     */
    public static FormatBehavior container() {
        return new FormatBehavior(true, false);
    }

    public static class Builder {
        private boolean hasChildren = false;
        private boolean isCompressible = false;

        public Builder hasChildren(boolean hasChildren) {
            this.hasChildren = hasChildren;
            return this;
        }

        public Builder isCompressible(boolean isCompressible) {
            this.isCompressible = isCompressible;
            return this;
        }

        public FormatBehavior build() {
            return new FormatBehavior(hasChildren, isCompressible);
        }
    }
}
