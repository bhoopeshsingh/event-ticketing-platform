package com.ticketing.booking.service;

import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.*;
import com.ticketing.common.entity.*;
import com.ticketing.common.util.TokenGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingService {

    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final BookingRepository bookingRepository;
    private final DistributedLockService lockService;
    private final SeatHoldCacheService cacheService;
    private final EventMessagingService messagingService;

    @Value("${booking.hold.duration.minutes:10}")
    private int defaultHoldDurationMinutes;

    @Value("${booking.max.seats.per.booking:10}")
    private int maxSeatsPerBooking;

    /**
     * Create a seat hold with distributed locking and TTL
     */
    @Transactional
    public SeatHoldResponse holdSeats(SeatHoldRequest request) {
        validateHoldRequest(request);

        String eventLockKey = DistributedLockService.eventBookingLock(request.getEventId());
        Duration lockTimeout = Duration.ofSeconds(30);

        try {
            return lockService.executeWithLock(eventLockKey, lockTimeout, () -> {
                return createSeatHoldInternal(request);
            });
        } catch (RuntimeException e) {
            log.error("Failed to acquire lock for seat hold request: {}", request, e);
            throw new BookingException("Unable to process seat hold request. Please try again.", e);
        }
    }

    private SeatHoldResponse createSeatHoldInternal(SeatHoldRequest request) {
        // 1. Validate seat availability
        List<Seat> seats = seatRepository.findByIdInWithLock(request.getSeatIds());
        validateSeatsForHold(seats, request);

        // 2. Check Redis cache for conflicting holds
        if (cacheService.areSeatsHeld(request.getSeatIds())) {
            throw new BookingException("One or more seats are currently held by another customer");
        }

        // 3. Check database for conflicting holds
        List<SeatHold> activeHolds = seatHoldRepository.findActiveHoldsForSeats(
            request.getEventId(), request.getSeatIds(), LocalDateTime.now());
        if (!activeHolds.isEmpty()) {
            throw new BookingException("Seats are already held. Please select different seats.");
        }

        // 4. Create seat hold
        LocalDateTime expiresAt = LocalDateTime.now()
            .plusMinutes(request.getHoldDurationMinutes());

        SeatHold seatHold = SeatHold.builder()
            .holdToken(TokenGenerator.generateHoldToken())
            .customerId(request.getCustomerId())
            .event(seats.get(0).getEvent()) // All seats belong to same event
            .seatIds(request.getSeatIds())
            .expiresAt(expiresAt)
            .status(SeatHold.HoldStatus.ACTIVE)
            .build();

        // 5. Update seat status to HELD
        int updatedSeats = seatRepository.holdSeats(request.getSeatIds());
        if (updatedSeats != request.getSeatIds().size()) {
            throw new BookingException("Some seats became unavailable during booking process");
        }

        // 6. Save hold to database
        seatHold = seatHoldRepository.save(seatHold);

        // 7. Cache the hold with TTL
        SeatHoldDto holdDto = convertToDto(seatHold);
        cacheService.cacheSeatHold(holdDto);

        // 8. Publish event for audit trail
        messagingService.publishSeatHoldCreated(holdDto, seats);

        // 9. Calculate total amount
        BigDecimal totalAmount = seats.stream()
            .map(Seat::getPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        String eventTitle = seats.get(0).getEvent().getTitle();

        log.info("Seat hold created successfully: {} for customer: {} with {} seats",
                seatHold.getHoldToken(), request.getCustomerId(), request.getSeatIds().size());

        // Create response manually
        SeatHoldResponse response = new SeatHoldResponse();
        response.setHoldToken(holdDto.getHoldToken());
        response.setCustomerId(holdDto.getCustomerId());
        response.setEventId(holdDto.getEventId());
        response.setEventTitle(eventTitle);
        response.setSeatCount(holdDto.getSeatCount());
        response.setTotalAmount(totalAmount);
        response.setExpiresAt(holdDto.getExpiresAt());
        response.setTimeRemainingSeconds(holdDto.getTimeRemainingSeconds());
        response.setStatus(holdDto.getStatus());
        response.setCreatedAt(holdDto.getCreatedAt());
        response.setMessage("Seats held successfully. Complete payment within " +
                (holdDto.getTimeRemainingSeconds() / 60) + " minutes.");

        return response;
    }

    /**
     * Confirm booking from seat hold
     */
    @Transactional
    public BookingDto confirmBooking(BookingConfirmRequest request) {
        validateConfirmRequest(request);

        // Get hold with lock
        SeatHold seatHold = seatHoldRepository.findByHoldTokenWithLock(request.getHoldToken())
            .orElseThrow(() -> new BookingNotFoundException("Seat hold not found: " + request.getHoldToken()));

        // Validate hold state
        if (!seatHold.isActive()) {
            throw new BookingException("Seat hold is not active or has expired");
        }

        if (!seatHold.getCustomerId().equals(request.getCustomerId())) {
            throw new BookingException("Invalid customer for this hold");
        }

        // Create booking
        Booking booking = Booking.builder()
            .bookingReference(TokenGenerator.generateBookingReference())
            .customerId(request.getCustomerId())
            .event(seatHold.getEvent())
            .seatIds(seatHold.getSeatIds())
            .totalAmount(calculateTotalAmount(seatHold.getSeatIds()))
            .status(Booking.BookingStatus.CONFIRMED)
            .holdToken(request.getHoldToken())
            .build();

        booking.confirm(request.getPaymentId());

        // Update seat status to BOOKED
        int bookedSeats = seatRepository.bookSeats(seatHold.getSeatIds());
        if (bookedSeats != seatHold.getSeatIds().size()) {
            throw new BookingException("Failed to confirm all seats");
        }

        // Update hold status
        seatHold.confirm();
        seatHoldRepository.save(seatHold);

        // Save booking
        booking = bookingRepository.save(booking);

        // Remove from cache
        cacheService.removeSeatHold(request.getHoldToken());

        // Publish events
        messagingService.publishBookingConfirmed(convertToDto(booking));
        messagingService.publishSeatHoldConfirmed(convertToDto(seatHold));

        log.info("Booking confirmed successfully: {} for hold: {}",
                booking.getBookingReference(), request.getHoldToken());

        return convertToDto(booking);
    }

    /**
     * Cancel seat hold
     */
    @Transactional
    public void cancelSeatHold(String holdToken, Long customerId) {
        SeatHold seatHold = seatHoldRepository.findByHoldTokenWithLock(holdToken)
            .orElseThrow(() -> new BookingNotFoundException("Seat hold not found: " + holdToken));

        if (!seatHold.getCustomerId().equals(customerId)) {
            throw new BookingException("Invalid customer for this hold");
        }

        if (seatHold.getStatus() != SeatHold.HoldStatus.ACTIVE) {
            throw new BookingException("Hold is not active");
        }

        // Release seats
        int releasedSeats = seatRepository.releaseSeats(seatHold.getSeatIds());
        log.debug("Released {} seats for hold: {}", releasedSeats, holdToken);

        // Update hold status
        seatHold.cancel();
        seatHoldRepository.save(seatHold);

        // Remove from cache
        cacheService.removeSeatHold(holdToken);

        // Publish event
        messagingService.publishSeatHoldCancelled(convertToDto(seatHold));

        log.info("Seat hold cancelled: {} by customer: {}", holdToken, customerId);
    }

    /**
     * Get booking by reference
     */
    public Optional<BookingDto> getBookingByReference(String bookingReference) {
        return bookingRepository.findByBookingReference(bookingReference)
            .map(this::convertToDto);
    }

    /**
     * Get seat hold by token
     */
    public Optional<SeatHoldDto> getSeatHold(String holdToken) {
        // Try cache first
        Optional<SeatHoldDto> cachedHold = cacheService.getSeatHold(holdToken);
        if (cachedHold.isPresent()) {
            return cachedHold;
        }

        // Fall back to database
        return seatHoldRepository.findByHoldToken(holdToken)
            .map(this::convertToDto);
    }

    // Validation methods
    private void validateHoldRequest(SeatHoldRequest request) {
        if (request.getSeatIds().isEmpty()) {
            throw new BookingException("Seat IDs cannot be empty");
        }

        if (request.getSeatIds().size() > maxSeatsPerBooking) {
            throw new BookingException("Cannot hold more than " + maxSeatsPerBooking + " seats at once");
        }

        if (request.getHoldDurationMinutes() < 1 || request.getHoldDurationMinutes() > 30) {
            throw new BookingException("Hold duration must be between 1 and 30 minutes");
        }
    }

    private void validateSeatsForHold(List<Seat> seats, SeatHoldRequest request) {
        if (seats.size() != request.getSeatIds().size()) {
            throw new BookingException("Some seats were not found");
        }

        // Check if all seats belong to the same event
        long distinctEventIds = seats.stream()
            .map(seat -> seat.getEvent().getId())
            .distinct()
            .count();
        if (distinctEventIds != 1) {
            throw new BookingException("All seats must belong to the same event");
        }

        // Check if all seats are available
        long availableSeats = seats.stream()
            .filter(Seat::isAvailable)
            .count();
        if (availableSeats != seats.size()) {
            throw new BookingException("One or more seats are not available");
        }

        // Verify event ID matches
        if (!seats.get(0).getEvent().getId().equals(request.getEventId())) {
            throw new BookingException("Event ID mismatch");
        }
    }

    private void validateConfirmRequest(BookingConfirmRequest request) {
        if (request.getHoldToken() == null || request.getHoldToken().trim().isEmpty()) {
            throw new BookingException("Hold token is required");
        }

        if (request.getPaymentId() == null || request.getPaymentId().trim().isEmpty()) {
            throw new BookingException("Payment ID is required");
        }
    }

    private BigDecimal calculateTotalAmount(List<Long> seatIds) {
        List<Seat> seats = seatRepository.findByIdIn(seatIds);
        return seats.stream()
            .map(Seat::getPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // DTO conversion methods
    private SeatHoldDto convertToDto(SeatHold seatHold) {
        return SeatHoldDto.builder()
            .id(seatHold.getId())
            .holdToken(seatHold.getHoldToken())
            .customerId(seatHold.getCustomerId())
            .eventId(seatHold.getEvent().getId())
            .seatIds(seatHold.getSeatIds())
            .expiresAt(seatHold.getExpiresAt())
            .status(seatHold.getStatus().name())
            .createdAt(seatHold.getCreatedAt())
            .build();
    }

    private BookingDto convertToDto(Booking booking) {
        return BookingDto.builder()
            .id(booking.getId())
            .bookingReference(booking.getBookingReference())
            .customerId(booking.getCustomerId())
            .eventId(booking.getEvent().getId())
            .seatIds(booking.getSeatIds())
            .totalAmount(booking.getTotalAmount())
            .status(booking.getStatus().name())
            .paymentId(booking.getPaymentId())
            .holdToken(booking.getHoldToken())
            .createdAt(booking.getCreatedAt())
            .confirmedAt(booking.getConfirmedAt())
            .cancelledAt(booking.getCancelledAt())
            .build();
    }
}
