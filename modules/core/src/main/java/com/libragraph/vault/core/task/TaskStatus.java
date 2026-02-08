package com.libragraph.vault.core.task;

public enum TaskStatus {
    OPEN(0, "OPEN"),
    IN_PROGRESS(1, "IN_PROGRESS"),
    BLOCKED(2, "BLOCKED"),
    BACKGROUND(3, "BACKGROUND"),
    COMPLETE(4, "COMPLETE"),
    ERROR(5, "ERROR"),
    CANCELLED(6, "CANCELLED"),
    DEAD(7, "DEAD");

    private final int id;
    private final String label;

    TaskStatus(int id, String label) {
        this.id = id;
        this.label = label;
    }

    public int id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static TaskStatus fromId(int id) {
        for (TaskStatus s : values()) {
            if (s.id == id) return s;
        }
        throw new IllegalArgumentException("Unknown TaskStatus id: " + id);
    }
}
