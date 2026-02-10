package com.ticketing.common.enums;

public enum EventStatus {
    DRAFT("Draft - Not yet published"),
    PUBLISHED("Published - Available for booking"),
    CANCELLED("Cancelled - Refunds processing"),
    COMPLETED("Completed - Event has ended");

    private final String description;

    EventStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBookable() {
        return this == PUBLISHED;
    }

    public boolean isActive() {
        return this == DRAFT || this == PUBLISHED;
    }
}
