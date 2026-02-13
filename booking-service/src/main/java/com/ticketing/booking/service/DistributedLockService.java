package com.ticketing.booking.service;

import java.time.Duration;

public interface DistributedLockService {

    /**
     * Acquire a distributed lock with automatic expiry
     *
     * @param lockKey The key for the lock
     * @param timeout Lock timeout duration
     * @return Lock token if successful, null if failed
     */
    String acquireLock(String lockKey, Duration timeout);

    /**
     * Release a distributed lock
     *
     * @param lockKey The key for the lock
     * @param lockToken The token that was returned when acquiring the lock
     * @return true if successfully released, false otherwise
     */
    boolean releaseLock(String lockKey, String lockToken);

    /**
     * Check if a lock exists
     *
     * @param lockKey The key for the lock
     * @return true if lock exists, false otherwise
     */
    boolean isLocked(String lockKey);

    /**
     * Execute a task while holding a distributed lock
     *
     * @param lockKey The key for the lock
     * @param timeout Lock timeout duration
     * @param task The task to execute while holding the lock
     * @return The result of the task execution
     * @throws RuntimeException if lock acquisition fails
     */
    <T> T executeWithLock(String lockKey, Duration timeout, DistributedTask<T> task);

    /**
     * Functional interface for tasks that need distributed locking
     */
    @FunctionalInterface
    interface DistributedTask<T> {
        T execute();
    }

    /**
     * Generate lock key for seat reservation
     */
    static String seatReservationLock(Long eventId, Long seatId) {
        return String.format("seat_reservation:%d:%d", eventId, seatId);
    }

    /**
     * Generate lock key for event booking
     */
    static String eventBookingLock(Long eventId) {
        return String.format("event_booking:%d", eventId);
    }

    /**
     * Generate lock key for customer booking (to prevent concurrent bookings)
     */
    static String customerBookingLock(Long customerId) {
        return String.format("customer_booking:%d", customerId);
    }
}
