package com.ticketing.booking.service;

import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.SeatHoldRequest;
import com.ticketing.common.dto.SeatHoldResponse;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.entity.SeatHold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatHoldRepository seatHoldRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private EventMessagingService messagingService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BookingService bookingService;

    private Event testEvent;
    private List<Seat> testSeats;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
            seatRepository,
            seatHoldRepository,
            bookingRepository,
            messagingService,
            redisTemplate
        );

        try {
            java.lang.reflect.Field durationField = BookingService.class.getDeclaredField("defaultHoldDurationMinutes");
            durationField.setAccessible(true);
            durationField.setInt(bookingService, 10);

            java.lang.reflect.Field maxSeatsField = BookingService.class.getDeclaredField("maxSeatsPerBooking");
            maxSeatsField.setAccessible(true);
            maxSeatsField.setInt(bookingService, 10);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set @Value fields", e);
        }

        testEvent = Event.builder()
            .id(1L)
            .title("Test Event")
            .availableSeats(100)
            .build();

        testSeats = List.of(
            Seat.builder()
                .id(1L)
                .event(testEvent)
                .section("VIP")
                .rowLetter("A")
                .seatNumber(1)
                .price(new BigDecimal("100.00"))
                .status(Seat.SeatStatus.AVAILABLE)
                .build(),
            Seat.builder()
                .id(2L)
                .event(testEvent)
                .section("VIP")
                .rowLetter("A")
                .seatNumber(2)
                .price(new BigDecimal("100.00"))
                .status(Seat.SeatStatus.AVAILABLE)
                .build()
        );
    }

    @Test
    void holdSeats_Success() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);

        when(seatRepository.holdSeatsGuarded(request.getSeatIds()))
            .thenReturn(2);

        when(seatRepository.findByIdIn(request.getSeatIds()))
            .thenReturn(testSeats);

        when(seatHoldRepository.save(any(SeatHold.class)))
            .thenAnswer(invocation -> {
                SeatHold hold = invocation.getArgument(0);
                hold.setId(1L);
                hold.setCreatedAt(LocalDateTime.now());
                return hold;
            });

        SeatHoldResponse response = bookingService.holdSeats(request);

        assertNotNull(response);
        assertEquals(1L, response.getCustomerId());
        assertEquals(1L, response.getEventId());
        assertEquals(2, response.getSeatCount());
        assertEquals(new BigDecimal("200.00"), response.getTotalAmount());
        assertNotNull(response.getHoldToken());

        verify(seatRepository).holdSeatsGuarded(request.getSeatIds());
        verify(seatHoldRepository).save(any(SeatHold.class));
        verify(messagingService).publishSeatHoldCreated(any(), any());
    }

    @Test
    void holdSeats_SeatAlreadyHeldInRedis_ThrowsException() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // First seat succeeds, second fails (already held)
        when(valueOperations.setIfAbsent(eq("seat:1:1:HELD"), anyString(), any(Duration.class)))
            .thenReturn(true);
        when(valueOperations.setIfAbsent(eq("seat:1:2:HELD"), anyString(), any(Duration.class)))
            .thenReturn(false);

        BookingException exception = assertThrows(
            BookingException.class,
            () -> bookingService.holdSeats(request)
        );

        assertTrue(exception.getMessage().contains("held by another customer"));
        verify(seatRepository, never()).holdSeatsGuarded(any());
        verify(seatHoldRepository, never()).save(any());
    }

    @Test
    void holdSeats_SeatBookedInDB_ThrowsException() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);

        // DB guard: one seat is BOOKED, so only 1 row updated instead of 2
        when(seatRepository.holdSeatsGuarded(request.getSeatIds()))
            .thenReturn(1);

        BookingException exception = assertThrows(
            BookingException.class,
            () -> bookingService.holdSeats(request)
        );

        assertTrue(exception.getMessage().contains("no longer available"));
        verify(seatHoldRepository, never()).save(any());
    }

    @Test
    void getSeatHold_FromDatabase() {
        String holdToken = "HOLD_123";

        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken(holdToken)
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        when(seatHoldRepository.findByHoldToken(holdToken))
            .thenReturn(Optional.of(seatHold));

        Optional<com.ticketing.common.dto.SeatHoldDto> result = bookingService.getSeatHold(holdToken);

        assertTrue(result.isPresent());
        assertEquals(holdToken, result.get().getHoldToken());
        verify(seatHoldRepository).findByHoldToken(holdToken);
    }

}
