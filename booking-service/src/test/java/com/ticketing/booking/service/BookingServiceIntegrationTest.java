package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.SeatHoldRequest;
import com.ticketing.common.dto.SeatHoldResponse;
import com.ticketing.common.entity.Event;
import com.ticketing.common.entity.Seat;
import com.ticketing.common.entity.SeatHold;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Disabled("Disabled until Mockito configuration is fixed")
@SpringBootTest
@ActiveProfiles("test")
class BookingServiceIntegrationTest {

    @MockBean
    private SeatRepository seatRepository;

    @MockBean
    private SeatHoldRepository seatHoldRepository;

    @MockBean
    private DistributedLockService lockService;

    @MockBean
    private SeatHoldCacheService cacheService;

    @MockBean
    private EventMessagingService messagingService;

    private BookingService bookingService;

    private Event testEvent;
    private List<Seat> testSeats;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
            seatRepository,
            seatHoldRepository,
            null, // bookingRepository not needed for this test
            lockService,
            cacheService,
            messagingService
        );

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
            .holdDurationMinutes(10)
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

        when(seatHoldRepository.findActiveHoldsForSeats(anyLong(), anyList(), any()))
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
        assertTrue(response.getTimeRemainingSeconds() > 0);

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
            .holdDurationMinutes(10)
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
    void holdSeats_InvalidSeatCount_ThrowsException() {
        // Arrange
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L)
            .eventId(1L)
            .seatIds(List.of()) // Empty seat list
            .holdDurationMinutes(10)
            .build();

        // Act & Assert
        BookingException exception = assertThrows(
            BookingException.class,
            () -> bookingService.holdSeats(request)
        );

        assertTrue(exception.getMessage().contains("cannot be empty"));
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

    @Test
    void getSeatHold_FallbackToDatabase() {
        // Arrange
        String holdToken = "HOLD_123";
        when(cacheService.getSeatHold(holdToken))
            .thenReturn(Optional.empty());

        SeatHold seatHold = createTestSeatHold();
        when(seatHoldRepository.findByHoldToken(holdToken))
            .thenReturn(Optional.of(seatHold));

        // Act
        Optional<com.ticketing.common.dto.SeatHoldDto> result = bookingService.getSeatHold(holdToken);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(holdToken, result.get().getHoldToken());

        // Verify fallback to database
        verify(cacheService).getSeatHold(holdToken);
        verify(seatHoldRepository).findByHoldToken(holdToken);
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

    private SeatHold createTestSeatHold() {
        return SeatHold.builder()
            .id(1L)
            .holdToken("HOLD_123")
            .customerId(1L)
            .event(testEvent)
            .seatIds(List.of(1L, 2L))
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .status(SeatHold.HoldStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
