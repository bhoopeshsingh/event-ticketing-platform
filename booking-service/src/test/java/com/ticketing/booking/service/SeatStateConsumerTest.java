package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.SeatHold;
import com.ticketing.common.service.SeatStatusCacheService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatStateConsumerTest {

    @Mock private SeatRepository seatRepository;
    @Mock private SeatHoldRepository seatHoldRepository;
    @Mock private EventMessagingService messagingService;
    @Mock private SeatStatusCacheService seatStatusCacheService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private SeatStateConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SeatStateConsumer(
            seatRepository, seatHoldRepository, messagingService, objectMapper, seatStatusCacheService
        );
    }

    @Test
    void onSeatStateTransition_SeatHoldExpired_ReleasesAndPublishes() {
        String json = "{\"eventType\":\"SEAT_HOLD_EXPIRED\",\"eventId\":1,\"seatId\":42}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "1:42", json);

        Event event = Event.builder().id(1L).build();
        SeatHold hold = SeatHold.builder()
            .id(1L).holdToken("HOLD_X").customerId(10L)
            .event(event).seatIds(List.of(42L))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        when(seatRepository.releaseSeats(Collections.singletonList(42L))).thenReturn(1);
        when(seatHoldRepository.findExpiredHoldsForSeat(eq(1L), eq(42L), any())).thenReturn(List.of(hold));
        when(seatHoldRepository.save(any())).thenReturn(hold);

        consumer.onSeatStateTransition(record);

        verify(seatRepository).releaseSeats(Collections.singletonList(42L));
        verify(seatStatusCacheService).transitionSeatStatus(1L, 42L, "HELD", "AVAILABLE");
        verify(seatHoldRepository).save(hold);
        verify(messagingService).publishSeatHoldExpired("HOLD_X", 10L, 1L, List.of(42L));
    }

    @Test
    void onSeatStateTransition_SeatAlreadyReleased_Skips() {
        String json = "{\"eventType\":\"SEAT_HOLD_EXPIRED\",\"eventId\":1,\"seatId\":42}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "1:42", json);

        when(seatRepository.releaseSeats(Collections.singletonList(42L))).thenReturn(0);

        consumer.onSeatStateTransition(record);

        verify(seatStatusCacheService, never()).transitionSeatStatus(any(), any(), any(), any());
        verify(seatHoldRepository, never()).findExpiredHoldsForSeat(any(), any(), any());
    }

    @Test
    void onSeatStateTransition_UnknownEventType_Ignored() {
        String json = "{\"eventType\":\"UNKNOWN_TYPE\",\"eventId\":1,\"seatId\":42}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", json);

        consumer.onSeatStateTransition(record);

        verify(seatRepository, never()).releaseSeats(any());
    }

    @Test
    void onSeatStateTransition_InvalidJson_HandledGracefully() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic", 0, 0, "key", "invalid-json{");

        consumer.onSeatStateTransition(record);

        verify(seatRepository, never()).releaseSeats(any());
    }
}
