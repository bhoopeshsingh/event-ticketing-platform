package com.ticketing.booking.service;

import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.dto.*;
import com.ticketing.common.entity.*;
import com.ticketing.common.service.SeatStatusCacheService;
import com.ticketing.common.util.TokenGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingService {

    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final BookingRepository bookingRepository;
    private final EventMessagingService messagingService;
    private final StringRedisTemplate redisTemplate;
    private final SeatStatusCacheService seatStatusCacheService;

    @Value("${booking.hold.duration.minutes:10}")
    private int defaultHoldDurationMinutes;

    @Value("${booking.max.seats.per.booking:10}")
    private int maxSeatsPerBooking;

    // Lua script for atomic lock release: only delete if value matches
    private static final String RELEASE_LOCK_LUA =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('DEL', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
        new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);

    /**
     * Build Redis key for a seat hold.
     * Pattern: seat:{eventId}:{seatId}:HELD
     */
    static String seatHoldKey(Long eventId, Long seatId) {
        return String.format("seat:%d:%d:HELD", eventId, seatId);
    }

    /**
     * Build Redis value for a seat hold.
     * Format: {customerId}:{holdToken}
     */
    private static String seatHoldValue(Long customerId, String holdToken) {
        return customerId + ":" + holdToken;
    }

    /**
     * Create a seat hold using per-seat Redis locks.
     * No event-level lock -- each seat is independently lockable via SET NX.
     */
    @Transactional
    public SeatHoldResponse holdSeats(SeatHoldRequest request) {
        validateHoldRequest(request);

        Long eventId = request.getEventId();
        String holdToken = TokenGenerator.generateHoldToken();
        String redisValue = seatHoldValue(request.getCustomerId(), holdToken);
        Duration holdDuration = Duration.ofMinutes(defaultHoldDurationMinutes);

        // 1. Acquire per-seat Redis locks (all-or-nothing)
        List<String> acquiredKeys = new ArrayList<>();
        boolean allAcquired = true;

        for (Long seatId : request.getSeatIds()) {
            String key = seatHoldKey(eventId, seatId);
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, redisValue, holdDuration);

            if (Boolean.TRUE.equals(acquired)) {
                acquiredKeys.add(key);
            } else {
                allAcquired = false;
                break;
            }
        }

        if (!allAcquired) {
            releaseRedisKeys(acquiredKeys, redisValue);
            throw new BookingException("One or more seats are currently held by another customer");
        }

        try {
            // 2. DB guard: update seats that are NOT booked
            //    Redis guarantees no concurrent hold conflict.
            //    DB guards against permanently sold (BOOKED) seats and handles Kafka-lag
            //    where an expired hold hasn't been cleaned up in DB yet.
            int updatedSeats = seatRepository.holdSeatsGuarded(request.getSeatIds());
            if (updatedSeats != request.getSeatIds().size()) {
                throw new BookingException("One or more selected seats are no longer available");
            }

            // 3. Fetch seat details for response building
            List<Seat> seats = seatRepository.findByIdIn(request.getSeatIds());

            // 4. Create seat hold record
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(defaultHoldDurationMinutes);

            SeatHold seatHold = SeatHold.builder()
                .holdToken(holdToken)
                .customerId(request.getCustomerId())
                .event(seats.get(0).getEvent())
                .seatIds(request.getSeatIds())
                .seatCount(request.getSeatIds().size())
                .expiresAt(expiresAt)
                .status(SeatHold.HoldStatus.ACTIVE)
                .build();

            seatHold = seatHoldRepository.save(seatHold);

            // 5. Build response (prepare DTOs before afterCommit to avoid lazy-load issues)
            SeatHoldDto holdDto = convertToDto(seatHold);
            List<Seat> seatsCopy = List.copyOf(seats);
            List<Long> seatIdsCopy = List.copyOf(request.getSeatIds());

            // 6. Redis HASH + Kafka side-effects after DB transaction completes
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        // DB commit succeeded: seats are HELD
                        seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "HELD");
                        messagingService.publishSeatHoldCreated(holdDto, seatsCopy);
                    } else {
                        // DB rolled back: seats remain AVAILABLE — re-affirm in HASH
                        seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "AVAILABLE");
                        log.warn("Seat hold rolled back for event={}, re-affirmed AVAILABLE in Redis HASH", eventId);
                    }
                }
            });

            // 7. Build response
            BigDecimal totalAmount = seats.stream()
                .map(Seat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            log.info("Seat hold created: token={} customer={} event={} seats={}",
                    holdToken, request.getCustomerId(), eventId, request.getSeatIds().size());

            SeatHoldResponse response = new SeatHoldResponse();
            response.setHoldToken(holdDto.getHoldToken());
            response.setCustomerId(holdDto.getCustomerId());
            response.setEventId(holdDto.getEventId());
            response.setEventTitle(seats.get(0).getEvent().getTitle());
            response.setSeatCount(holdDto.getSeatCount());
            response.setTotalAmount(totalAmount);
            response.setExpiresAt(holdDto.getExpiresAt());
            response.setTimeRemainingSeconds(holdDto.getTimeRemainingSeconds());
            response.setStatus(holdDto.getStatus());
            response.setCreatedAt(holdDto.getCreatedAt());
            response.setMessage("Seats held successfully. Complete payment within " +
                    defaultHoldDurationMinutes + " minutes.");

            return response;

        } catch (Exception e) {
            releaseRedisKeys(acquiredKeys, redisValue);
            throw e;
        }
    }

    /**
     * Release Redis keys atomically using Lua script.
     * Only deletes the key if its value matches (prevents releasing someone else's lock).
     */
    private void releaseRedisKeys(List<String> keys, String expectedValue) {
        for (String key : keys) {
            try {
                redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(key), expectedValue);
            } catch (Exception e) {
                log.error("Failed to release Redis key: {}", key, e);
            }
        }
    }

    /**
     * Confirm booking from seat hold
     */
    @Transactional
    public BookingDto confirmBooking(BookingConfirmRequest request) {
        validateConfirmRequest(request);

        SeatHold seatHold = seatHoldRepository.findByHoldToken(request.getHoldToken())
            .orElseThrow(() -> new BookingNotFoundException("Seat hold not found: " + request.getHoldToken()));

        if (!seatHold.isActive()) {
            throw new BookingException("Seat hold is not active or has expired");
        }

        if (!seatHold.getCustomerId().equals(request.getCustomerId())) {
            throw new BookingException("Invalid customer for this hold");
        }

        Long eventId = seatHold.getEvent().getId();
        String expectedValue = seatHoldValue(seatHold.getCustomerId(), seatHold.getHoldToken());

        // DB is the source of truth for confirmation.
        // Bookings succeed even when Redis restarts and hold keys are lost.
        // Guards: (1) seatHold.isActive() above, (2) bookSeats WHERE status='HELD' below.

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

        // Update seats from HELD to BOOKED
        int bookedSeats = seatRepository.bookSeats(seatHold.getSeatIds());
        if (bookedSeats != seatHold.getSeatIds().size()) {
            throw new BookingException("Failed to confirm all seats. Some may have been released.");
        }

        seatHold.confirm();
        seatHoldRepository.save(seatHold);
        booking = bookingRepository.save(booking);

        // Prepare DTOs before afterCommit to avoid lazy-load issues
        BookingDto bookingDto = convertToDto(booking);
        SeatHoldDto holdDto = convertToDto(seatHold);
        List<Long> seatIdsCopy = List.copyOf(seatHold.getSeatIds());
        List<String> keysToDelete = seatIdsCopy.stream()
            .map(seatId -> seatHoldKey(eventId, seatId))
            .toList();

        // Redis HASH + Kafka side-effects after DB transaction completes
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    // DB commit succeeded: seats are BOOKED
                    seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "BOOKED");
                    releaseRedisKeys(keysToDelete, expectedValue);
                    messagingService.publishBookingConfirmed(bookingDto);
                    messagingService.publishSeatHoldConfirmed(holdDto);
                } else {
                    // DB rolled back: seats remain HELD — re-affirm in HASH
                    // (handles Redis restart where HASH may have lost HELD entries)
                    seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "HELD");
                    log.warn("Booking confirmation rolled back for hold={}, re-affirmed HELD in Redis HASH",
                            request.getHoldToken());
                }
            }
        });

        log.info("Booking confirmed: ref={} hold={} customer={}",
                bookingDto.getBookingReference(), request.getHoldToken(), request.getCustomerId());

        return bookingDto;
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

        Long eventId = seatHold.getEvent().getId();
        String expectedValue = seatHoldValue(customerId, holdToken);

        // Release seats in DB
        int releasedSeats = seatRepository.releaseSeats(seatHold.getSeatIds());
        log.debug("Released {} seats for hold: {}", releasedSeats, holdToken);

        seatHold.cancel();
        seatHoldRepository.save(seatHold);

        // Prepare before afterCommit to avoid lazy-load issues
        SeatHoldDto holdDto = convertToDto(seatHold);
        List<Long> seatIdsCopy = List.copyOf(seatHold.getSeatIds());
        List<String> keysToDelete = seatIdsCopy.stream()
            .map(seatId -> seatHoldKey(eventId, seatId))
            .toList();

        // Redis HASH + Kafka side-effects after DB transaction completes
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    // DB commit succeeded: seats are AVAILABLE
                    seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "AVAILABLE");
                    releaseRedisKeys(keysToDelete, expectedValue);
                    messagingService.publishSeatHoldCancelled(holdDto);
                } else {
                    // DB rolled back: seats remain HELD — re-affirm in HASH
                    seatStatusCacheService.cacheSeatStatusChanges(eventId, seatIdsCopy, "HELD");
                    log.warn("Seat hold cancellation rolled back for hold={}, re-affirmed HELD in Redis HASH",
                            holdToken);
                }
            }
        });

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

    }

    private void validateSeatsExist(List<Seat> seats, SeatHoldRequest request) {
        if (seats.size() != request.getSeatIds().size()) {
            throw new BookingException("Some seats were not found");
        }

        long distinctEventIds = seats.stream()
            .map(seat -> seat.getEvent().getId())
            .distinct()
            .count();
        if (distinctEventIds != 1) {
            throw new BookingException("All seats must belong to the same event");
        }

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
