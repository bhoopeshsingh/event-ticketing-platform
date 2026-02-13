package com.ticketing.booking.service;

import com.ticketing.common.dto.SeatHoldDto;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SeatHoldCacheService {
    void cacheSeatHold(SeatHoldDto seatHold);
    Optional<SeatHoldDto> getSeatHold(String holdToken);
    void removeSeatHold(String holdToken);
    boolean areSeatsHeld(List<Long> seatIds);
    Set<String> getCustomerHoldTokens(Long customerId);
    void updateHoldStatus(String holdToken, String newStatus);
    Duration getHoldTTL(String holdToken);
    boolean extendHoldExpiry(String holdToken, Duration additionalTime);
}
