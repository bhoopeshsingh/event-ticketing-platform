package com.ticketing.common.util;

import java.security.SecureRandom;
import java.util.UUID;

public class TokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * Generate a unique hold token
     */
    public static String generateHoldToken() {
        return "HOLD_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * Generate a booking reference (user-friendly)
     */
    public static String generateBookingReference() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Generate a queue admission token
     */
    public static String generateQueueToken() {
        return "QUEUE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * Generate idempotency key
     */
    public static String generateIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
