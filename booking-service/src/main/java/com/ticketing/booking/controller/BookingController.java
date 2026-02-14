package com.ticketing.booking.controller;

import com.ticketing.booking.service.BookingException;
import com.ticketing.booking.service.BookingNotFoundException;
import com.ticketing.booking.service.BookingService;
import com.ticketing.common.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Booking Controller", description = "Seat reservation and booking management")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/hold")
    @Operation(
        summary = "Hold seats for booking",
        description = "Reserve specific seats with time-bound hold (booking.hold.duration.minutes). " +
                     "This is the core write operation that implements distributed locking " +
                     "and prevents double-booking through Redis TTL mechanism."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Seats held successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or seats unavailable"),
        @ApiResponse(responseCode = "409", description = "Seats already held by another customer"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<SeatHoldResponse> holdSeats(
            @Valid @RequestBody SeatHoldRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Seat hold request received for customer: {} event: {} seats: {}",
                request.getCustomerId(), request.getEventId(), request.getSeatIds().size());

        try {
            if (idempotencyKey != null) {
                request.setIdempotencyKey(idempotencyKey);
            }

            SeatHoldResponse response = bookingService.holdSeats(request);

            log.info("Seat hold successful: {} for customer: {}",
                    response.getHoldToken(), request.getCustomerId());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (BookingException e) {
            log.warn("Seat hold failed for customer: {} - {}", request.getCustomerId(), e.getMessage());
            throw e; // Will be handled by global exception handler
        }
    }

    @PostMapping("/{holdToken}/confirm")
    @Operation(
        summary = "Confirm booking from seat hold",
        description = "Complete the booking process by providing payment information. " +
                     "Converts the temporary seat hold into a confirmed booking."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Booking confirmed successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid confirmation request"),
        @ApiResponse(responseCode = "404", description = "Seat hold not found"),
        @ApiResponse(responseCode = "410", description = "Seat hold expired"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BookingDto> confirmBooking(
            @Parameter(description = "Hold token from seat hold response") @PathVariable String holdToken,
            @Valid @RequestBody BookingConfirmRequest request) {

        log.info("Booking confirmation request for hold: {} customer: {}",
                holdToken, request.getCustomerId());

        try {
            request.setHoldToken(holdToken); // Ensure consistency
            BookingDto booking = bookingService.confirmBooking(request);

            log.info("Booking confirmed successfully: {} for hold: {}",
                    booking.getBookingReference(), holdToken);

            return ResponseEntity.ok(booking);

        } catch (BookingNotFoundException e) {
            log.warn("Booking confirmation failed - hold not found: {}", holdToken);
            throw e;
        } catch (BookingException e) {
            log.warn("Booking confirmation failed for hold: {} - {}", holdToken, e.getMessage());
            throw e;
        }
    }

    @DeleteMapping("/hold/{holdToken}")
    @Operation(
        summary = "Cancel seat hold",
        description = "Cancel an active seat hold, releasing the reserved seats back to available pool."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Seat hold cancelled successfully"),
        @ApiResponse(responseCode = "404", description = "Seat hold not found"),
        @ApiResponse(responseCode = "400", description = "Hold cannot be cancelled"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<Void> cancelSeatHold(
            @Parameter(description = "Hold token to cancel") @PathVariable String holdToken,
            @RequestParam Long customerId) {

        log.info("Seat hold cancellation request for hold: {} customer: {}", holdToken, customerId);

        try {
            bookingService.cancelSeatHold(holdToken, customerId);

            log.info("Seat hold cancelled successfully: {} for customer: {}", holdToken, customerId);

            return ResponseEntity.noContent().build();

        } catch (BookingNotFoundException e) {
            log.warn("Seat hold cancellation failed - hold not found: {}", holdToken);
            throw e;
        } catch (BookingException e) {
            log.warn("Seat hold cancellation failed for hold: {} - {}", holdToken, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/hold/{holdToken}")
    @Operation(
        summary = "Get seat hold details",
        description = "Retrieve details of an existing seat hold including remaining time."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seat hold found"),
        @ApiResponse(responseCode = "404", description = "Seat hold not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<SeatHoldDto> getSeatHold(
            @Parameter(description = "Hold token to lookup") @PathVariable String holdToken) {

        log.debug("Seat hold lookup request for: {}", holdToken);

        Optional<SeatHoldDto> seatHold = bookingService.getSeatHold(holdToken);

        if (seatHold.isPresent()) {
            return ResponseEntity.ok(seatHold.get());
        } else {
            log.debug("Seat hold not found: {}", holdToken);
            throw new BookingNotFoundException("Seat hold not found: " + holdToken);
        }
    }

    @GetMapping("/{bookingReference}")
    @Operation(
        summary = "Get booking details",
        description = "Retrieve complete booking information by booking reference."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Booking found"),
        @ApiResponse(responseCode = "404", description = "Booking not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BookingDto> getBooking(
            @Parameter(description = "Booking reference number") @PathVariable String bookingReference) {

        log.debug("Booking lookup request for: {}", bookingReference);

        Optional<BookingDto> booking = bookingService.getBookingByReference(bookingReference);

        if (booking.isPresent()) {
            return ResponseEntity.ok(booking.get());
        } else {
            log.debug("Booking not found: {}", bookingReference);
            throw new BookingNotFoundException("Booking not found: " + bookingReference);
        }
    }

    @GetMapping("/health")
    @Operation(
        summary = "Health check endpoint",
        description = "Simple health check for load balancer and monitoring."
    )
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Booking Service is healthy");
    }
}
