package com.ticketing.booking.service;

import com.ticketing.common.dto.BookingDto;
import com.ticketing.common.dto.SeatHoldDto;
import com.ticketing.common.entity.Seat;

import java.util.List;

public interface EventMessagingService {
    void publishSeatHoldCreated(SeatHoldDto seatHold, List<Seat> seats);
    void publishSeatHoldConfirmed(SeatHoldDto seatHold);
    void publishSeatHoldCancelled(SeatHoldDto seatHold);
    void publishSeatHoldExpired(String holdToken, Long customerId, Long eventId, List<Long> seatIds);
    void publishBookingConfirmed(BookingDto booking);
}
