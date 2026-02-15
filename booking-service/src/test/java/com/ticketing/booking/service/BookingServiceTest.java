package com.ticketing.booking.service;

import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.*;
import com.ticketing.common.entity.Booking;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.entity.SeatHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private com.ticketing.common.service.SeatStatusCacheService seatStatusCacheService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BookingService bookingService;

    private Event testEvent;
    private List<Seat> testSeats;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();

        bookingService = new BookingService(
            seatRepository,
            seatHoldRepository,
            bookingRepository,
            messagingService,
            redisTemplate,
            seatStatusCacheService
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

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ─── holdSeats tests ────────────────────────────────────────────────

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
        assertTrue(response.getMessage().contains("Seats held successfully"));

        verify(seatRepository).holdSeatsGuarded(request.getSeatIds());
        verify(seatHoldRepository).save(any(SeatHold.class));

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("HELD"));
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
    void holdSeats_RedisDown_FallsBackToDbLocking() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("Connection refused"));

        when(seatRepository.findByIdInForUpdate(request.getSeatIds()))
            .thenReturn(testSeats);
        when(seatRepository.holdSeats(request.getSeatIds()))
            .thenReturn(2);

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
        assertEquals(2, response.getSeatCount());
        assertTrue(response.getMessage().contains("degraded mode"));

        verify(seatRepository).findByIdInForUpdate(request.getSeatIds());
        verify(seatRepository).holdSeats(request.getSeatIds());
        verify(seatRepository, never()).holdSeatsGuarded(any());

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("HELD"));
        verify(messagingService).publishSeatHoldCreated(any(), any());
    }

    @Test
    void holdSeats_RedisDown_DegradedMode_DBRejectsSeats() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("Connection refused"));

        when(seatRepository.findByIdInForUpdate(request.getSeatIds()))
            .thenReturn(testSeats);
        when(seatRepository.holdSeats(request.getSeatIds()))
            .thenReturn(1); // Only 1 of 2 seats available

        assertThrows(BookingException.class, () -> bookingService.holdSeats(request));
    }

    @Test
    void holdSeats_WrappedRedisConnectionError_FallsBackToDb() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Wrapped RedisConnectionFailureException
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RuntimeException("Wrapped", new RedisConnectionFailureException("refused")));

        when(seatRepository.findByIdInForUpdate(request.getSeatIds())).thenReturn(testSeats);
        when(seatRepository.holdSeats(request.getSeatIds())).thenReturn(2);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(invocation -> {
            SeatHold hold = invocation.getArgument(0);
            hold.setId(1L);
            hold.setCreatedAt(LocalDateTime.now());
            return hold;
        });

        SeatHoldResponse response = bookingService.holdSeats(request);
        assertNotNull(response);
        assertTrue(response.getMessage().contains("degraded mode"));
    }

    @Test
    void holdSeats_NonRedisException_Rethrown() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new IllegalStateException("Some other error"));

        assertThrows(IllegalStateException.class, () -> bookingService.holdSeats(request));
    }

    @Test
    void holdSeats_EmptySeatIds_ThrowsValidation() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of())
            .build();

        assertThrows(BookingException.class, () -> bookingService.holdSeats(request));
    }

    @Test
    void holdSeats_TooManySeats_ThrowsValidation() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L))
            .build();

        assertThrows(BookingException.class, () -> bookingService.holdSeats(request));
    }

    @Test
    void holdSeats_Rollback_ReAffirmsAvailableInRedis() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
        when(seatRepository.holdSeatsGuarded(request.getSeatIds())).thenReturn(2);
        when(seatRepository.findByIdIn(request.getSeatIds())).thenReturn(testSeats);
        when(seatHoldRepository.save(any(SeatHold.class))).thenAnswer(inv -> {
            SeatHold h = inv.getArgument(0);
            h.setId(1L);
            h.setCreatedAt(LocalDateTime.now());
            return h;
        });

        bookingService.holdSeats(request);

        // Simulate rollback
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("AVAILABLE"));
    }

    // ─── confirmBooking tests ───────────────────────────────────────────

    @Test
    void confirmBooking_Success() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_ABC")).thenReturn(Optional.of(seatHold));
        when(seatRepository.bookSeats(List.of(1L, 2L))).thenReturn(2);
        when(seatRepository.findByIdIn(List.of(1L, 2L))).thenReturn(testSeats);
        when(seatHoldRepository.save(any(SeatHold.class))).thenReturn(seatHold);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(1L);
            b.setCreatedAt(LocalDateTime.now());
            return b;
        });

        BookingDto result = bookingService.confirmBooking(request);

        assertNotNull(result);
        assertEquals(1L, result.getCustomerId());
        assertEquals("CONFIRMED", result.getStatus());
        verify(seatRepository).bookSeats(List.of(1L, 2L));
        verify(bookingRepository).save(any(Booking.class));

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("BOOKED"));
        verify(messagingService).publishBookingConfirmed(any());
        verify(messagingService).publishSeatHoldConfirmed(any());
    }

    @Test
    void confirmBooking_HoldNotFound_ThrowsNotFoundException() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("UNKNOWN_TOKEN")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("UNKNOWN_TOKEN")).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class, () -> bookingService.confirmBooking(request));
    }

    @Test
    void confirmBooking_HoldExpired_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_EXPIRED")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().minusMinutes(5)) // already expired
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_EXPIRED")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_EXPIRED")).thenReturn(Optional.of(seatHold));

        assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
    }

    @Test
    void confirmBooking_WrongCustomer_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC")
            .customerId(999L) // different customer
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_ABC")).thenReturn(Optional.of(seatHold));

        BookingException ex = assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
        assertTrue(ex.getMessage().contains("Invalid customer"));
    }

    @Test
    void confirmBooking_PartialBookFailure_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_ABC")).thenReturn(Optional.of(seatHold));
        when(seatRepository.findByIdIn(List.of(1L, 2L))).thenReturn(testSeats);
        when(seatRepository.bookSeats(List.of(1L, 2L))).thenReturn(1); // only 1 booked

        assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
    }

    @Test
    void confirmBooking_HoldAlreadyConfirmed_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_CONFIRMED")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.CONFIRMED) // not ACTIVE
            .build();

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_CONFIRMED")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_CONFIRMED")).thenReturn(Optional.of(seatHold));

        assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
    }

    @Test
    void confirmBooking_Rollback_ReAffirmsHeldInRedis() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        when(seatHoldRepository.findByHoldToken("HOLD_ABC")).thenReturn(Optional.of(seatHold));
        when(seatRepository.bookSeats(List.of(1L, 2L))).thenReturn(2);
        when(seatRepository.findByIdIn(List.of(1L, 2L))).thenReturn(testSeats);
        when(seatHoldRepository.save(any())).thenReturn(seatHold);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(1L);
            b.setCreatedAt(LocalDateTime.now());
            return b;
        });

        bookingService.confirmBooking(request);

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("HELD"));
    }

    @Test
    void confirmBooking_EmptyHoldToken_ThrowsValidation() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("")
            .customerId(1L)
            .paymentId("PAY_123")
            .build();

        assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
    }

    @Test
    void confirmBooking_EmptyPaymentId_ThrowsValidation() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC")
            .customerId(1L)
            .paymentId("")
            .build();

        assertThrows(BookingException.class, () -> bookingService.confirmBooking(request));
    }

    // ─── cancelSeatHold tests ───────────────────────────────────────────

    @Test
    void cancelSeatHold_Success() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_CANCEL")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        when(seatHoldRepository.findByHoldTokenWithLock("HOLD_CANCEL")).thenReturn(Optional.of(seatHold));
        when(seatRepository.releaseSeats(List.of(1L, 2L))).thenReturn(2);
        when(seatHoldRepository.save(any())).thenReturn(seatHold);

        bookingService.cancelSeatHold("HOLD_CANCEL", 1L);

        verify(seatRepository).releaseSeats(List.of(1L, 2L));
        assertEquals(SeatHold.HoldStatus.CANCELLED, seatHold.getStatus());

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("AVAILABLE"));
        verify(messagingService).publishSeatHoldCancelled(any());
    }

    @Test
    void cancelSeatHold_NotFound_ThrowsNotFoundException() {
        when(seatHoldRepository.findByHoldTokenWithLock("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class, () -> bookingService.cancelSeatHold("UNKNOWN", 1L));
    }

    @Test
    void cancelSeatHold_WrongCustomer_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_OTHER")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        when(seatHoldRepository.findByHoldTokenWithLock("HOLD_OTHER")).thenReturn(Optional.of(seatHold));

        BookingException ex = assertThrows(BookingException.class,
            () -> bookingService.cancelSeatHold("HOLD_OTHER", 999L));
        assertTrue(ex.getMessage().contains("Invalid customer"));
    }

    @Test
    void cancelSeatHold_NotActive_ThrowsException() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_EXPIRED")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.EXPIRED)
            .build();

        when(seatHoldRepository.findByHoldTokenWithLock("HOLD_EXPIRED")).thenReturn(Optional.of(seatHold));

        BookingException ex = assertThrows(BookingException.class,
            () -> bookingService.cancelSeatHold("HOLD_EXPIRED", 1L));
        assertTrue(ex.getMessage().contains("not active"));
    }

    @Test
    void cancelSeatHold_Rollback_ReAffirmsHeldInRedis() {
        SeatHold seatHold = SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_CANCEL")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();
        seatHold.setCreatedAt(LocalDateTime.now());

        when(seatHoldRepository.findByHoldTokenWithLock("HOLD_CANCEL")).thenReturn(Optional.of(seatHold));
        when(seatRepository.releaseSeats(List.of(1L, 2L))).thenReturn(2);
        when(seatHoldRepository.save(any())).thenReturn(seatHold);

        bookingService.cancelSeatHold("HOLD_CANCEL", 1L);

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(seatStatusCacheService).cacheSeatStatusChanges(eq(1L), eq(List.of(1L, 2L)), eq("HELD"));
    }

    // ─── getBookingByReference tests ────────────────────────────────────

    @Test
    void getBookingByReference_Found() {
        Booking booking = Booking.builder()
            .id(1L)
            .bookingReference("BK-ABC123")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L))
            .totalAmount(new BigDecimal("100.00"))
            .status(Booking.BookingStatus.CONFIRMED)
            .paymentId("PAY_1")
            .holdToken("HOLD_1")
            .build();
        booking.setCreatedAt(LocalDateTime.now());

        when(bookingRepository.findByBookingReference("BK-ABC123")).thenReturn(Optional.of(booking));

        Optional<BookingDto> result = bookingService.getBookingByReference("BK-ABC123");

        assertTrue(result.isPresent());
        assertEquals("BK-ABC123", result.get().getBookingReference());
        assertEquals("CONFIRMED", result.get().getStatus());
    }

    @Test
    void getBookingByReference_NotFound() {
        when(bookingRepository.findByBookingReference("UNKNOWN")).thenReturn(Optional.empty());

        Optional<BookingDto> result = bookingService.getBookingByReference("UNKNOWN");
        assertTrue(result.isEmpty());
    }

    // ─── getSeatHold tests ──────────────────────────────────────────────

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

        Optional<SeatHoldDto> result = bookingService.getSeatHold(holdToken);

        assertTrue(result.isPresent());
        assertEquals(holdToken, result.get().getHoldToken());
        verify(seatHoldRepository).findByHoldToken(holdToken);
    }

    @Test
    void getSeatHold_NotFound() {
        when(seatHoldRepository.findByHoldToken("MISSING")).thenReturn(Optional.empty());

        Optional<SeatHoldDto> result = bookingService.getSeatHold("MISSING");
        assertTrue(result.isEmpty());
    }

    // ─── seatHoldKey tests ──────────────────────────────────────────────

    @Test
    void seatHoldKey_Format() {
        assertEquals("seat:1:2:HELD", BookingService.seatHoldKey(1L, 2L));
        assertEquals("seat:100:999:HELD", BookingService.seatHoldKey(100L, 999L));
    }
}
