package com.libragraph.vault.types;

public enum EntryType {
    FILE(0, "file"),
    DIRECTORY(1, "directory"),
    SYMLINK(2, "symlink");

    private final int id;
    private final String label;

    EntryType(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static EntryType fromId(int id) {
        for (EntryType t : values()) {
            if (t.id == id) return t;
        }
        throw new IllegalArgumentException("Unknown EntryType id: " + id);
    }
}
