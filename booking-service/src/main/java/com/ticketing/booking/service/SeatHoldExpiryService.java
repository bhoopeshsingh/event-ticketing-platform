package com.ticketing.booking.service;

import org.springframework.data.redis.connection.MessageListener;

public interface SeatHoldExpiryService extends MessageListener {
    void init();
    void handleExpiredSeatHold(String holdToken);
    void cleanupExpiredHolds();
    int bulkMarkExpiredHolds();
}
