package edu.advising.auth;

public enum ApplicationStatus {
    PENDING,
    ACTIVE,
    ALL;

    public static ApplicationStatus from(String value) {
        if (value == null || value.isBlank()) return PENDING;

        try{
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
