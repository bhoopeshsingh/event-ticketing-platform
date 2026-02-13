package com.ticketing.booking.service;

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

import java.math.BigDecimal;
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
    private DistributedLockService lockService;

    @Mock
    private SeatHoldCacheService cacheService;

    @Mock
    private EventMessagingService messagingService;

    @Mock
    private StringRedisTemplate redisTemplate;

    private BookingService bookingService;

    private Event testEvent;
    private List<Seat> testSeats;

    @BeforeEach
    void setUp() {
        // Use real bookingService with mocks
        bookingService = new BookingService(
            seatRepository,
            seatHoldRepository,
            null, // bookingRepository not needed for these tests
            lockService,
            cacheService,
            messagingService,
            redisTemplate
        );

        // Reflection or setter could be used for @Value fields if needed, 
        // but default values might suffice or constructor injection if possible.
        // For now, relying on default values or if they are field injected, we need a way to set them.
        // Since they are @Value annotated fields, we might need a workaround for pure unit test without Spring context.
        // A common pattern is to make them package-private and set them, or use ReflectionTestUtils.
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

        // Create test data
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
        // Arrange
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(lockService.executeWithLock(anyString(), any(), any()))
            .thenAnswer(invocation -> {
                DistributedLockService.DistributedTask<?> task = invocation.getArgument(2);
                return task.execute();
            });

        when(seatRepository.findByIdInWithLock(request.getSeatIds()))
            .thenReturn(testSeats);

        when(cacheService.areSeatsHeld(request.getSeatIds()))
            .thenReturn(false);

        when(seatHoldRepository.findActiveHoldsForSeats(anyLong(), any(Long[].class), any()))
            .thenReturn(List.of());

        when(seatRepository.holdSeats(request.getSeatIds()))
            .thenReturn(2);

        when(seatHoldRepository.save(any(SeatHold.class)))
            .thenAnswer(invocation -> {
                SeatHold hold = invocation.getArgument(0);
                hold.setId(1L);
                hold.setCreatedAt(LocalDateTime.now());
                return hold;
            });

        // Act
        SeatHoldResponse response = bookingService.holdSeats(request);

        // Assert
        assertNotNull(response);
        assertEquals(1L, response.getCustomerId());
        assertEquals(1L, response.getEventId());
        assertEquals(2, response.getSeatCount());
        assertEquals(new BigDecimal("200.00"), response.getTotalAmount());
        assertNotNull(response.getHoldToken());

        // Verify interactions
        verify(lockService).executeWithLock(anyString(), any(), any());
        verify(seatRepository).findByIdInWithLock(request.getSeatIds());
        verify(cacheService).areSeatsHeld(request.getSeatIds());
        verify(seatRepository).holdSeats(request.getSeatIds());
        verify(seatHoldRepository).save(any(SeatHold.class));
        verify(cacheService).cacheSeatHold(any());
        verify(messagingService).publishSeatHoldCreated(any(), any());
    }

    @Test
    void holdSeats_SeatsAlreadyHeld_ThrowsException() {
        // Arrange
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .build();

        when(lockService.executeWithLock(anyString(), any(), any()))
            .thenAnswer(invocation -> {
                DistributedLockService.DistributedTask<?> task = invocation.getArgument(2);
                return task.execute();
            });

        when(seatRepository.findByIdInWithLock(request.getSeatIds()))
            .thenReturn(testSeats);

        when(cacheService.areSeatsHeld(request.getSeatIds()))
            .thenReturn(true); // Seats are held in cache

        // Act & Assert
        BookingException exception = assertThrows(
            BookingException.class,
            () -> bookingService.holdSeats(request)
        );

        assertTrue(exception.getMessage().contains("held by another customer"));

        // Verify that no seats were actually held
        verify(seatRepository, never()).holdSeats(any());
        verify(seatHoldRepository, never()).save(any());
    }

    @Test
    void getSeatHold_FromCache() {
        // Arrange
        String holdToken = "HOLD_123";
        when(cacheService.getSeatHold(holdToken))
            .thenReturn(Optional.of(createTestSeatHoldDto()));

        // Act
        Optional<com.ticketing.common.dto.SeatHoldDto> result = bookingService.getSeatHold(holdToken);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(holdToken, result.get().getHoldToken());

        // Verify cache was checked first
        verify(cacheService).getSeatHold(holdToken);
        verify(seatHoldRepository, never()).findByHoldToken(holdToken);
    }

    private com.ticketing.common.dto.SeatHoldDto createTestSeatHoldDto() {
        return com.ticketing.common.dto.SeatHoldDto.builder()
            .id(1L)
            .holdToken("HOLD_123")
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .status("ACTIVE")
            .createdAt(LocalDateTime.now())
            .build();
    }
}
