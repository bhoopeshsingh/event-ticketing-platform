package com.ticketing.booking.controller;

import com.ticketing.booking.service.BookingException;
import com.ticketing.booking.service.BookingNotFoundException;
import com.ticketing.booking.service.BookingService;
import com.ticketing.common.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @InjectMocks
    private BookingController bookingController;

    // ─── holdSeats ──────────────────────────────────────────────────────

    @Test
    void holdSeats_Success_Returns201() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L).eventId(1L).seatIds(List.of(1L, 2L)).build();

        SeatHoldResponse response = new SeatHoldResponse();
        response.setHoldToken("HOLD_ABC");
        response.setCustomerId(1L);
        response.setSeatCount(2);

        when(bookingService.holdSeats(any())).thenReturn(response);

        ResponseEntity<SeatHoldResponse> result = bookingController.holdSeats(request, null);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals("HOLD_ABC", result.getBody().getHoldToken());
    }

    @Test
    void holdSeats_WithIdempotencyKey() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L).eventId(1L).seatIds(List.of(1L)).build();

        SeatHoldResponse response = new SeatHoldResponse();
        response.setHoldToken("HOLD_XYZ");

        when(bookingService.holdSeats(any())).thenReturn(response);

        ResponseEntity<SeatHoldResponse> result = bookingController.holdSeats(request, "idem-123");

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals("idem-123", request.getIdempotencyKey());
    }

    @Test
    void holdSeats_BookingException_Rethrown() {
        SeatHoldRequest request = SeatHoldRequest.builder()
            .customerId(1L).eventId(1L).seatIds(List.of(1L)).build();

        when(bookingService.holdSeats(any())).thenThrow(new BookingException("Seats unavailable"));

        assertThrows(BookingException.class, () -> bookingController.holdSeats(request, null));
    }

    // ─── confirmBooking ─────────────────────────────────────────────────

    @Test
    void confirmBooking_Success_Returns200() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_ABC").customerId(1L).paymentId("PAY_123").build();

        BookingDto booking = BookingDto.builder()
            .id(1L).bookingReference("BK-001").customerId(1L)
            .status("CONFIRMED").build();

        when(bookingService.confirmBooking(any())).thenReturn(booking);

        ResponseEntity<BookingDto> result = bookingController.confirmBooking("HOLD_ABC", request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("BK-001", result.getBody().getBookingReference());
        assertEquals("HOLD_ABC", request.getHoldToken()); // Ensured consistency
    }

    @Test
    void confirmBooking_HoldNotFound_Rethrown() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("UNKNOWN").customerId(1L).paymentId("PAY_123").build();

        when(bookingService.confirmBooking(any()))
            .thenThrow(new BookingNotFoundException("Not found"));

        assertThrows(BookingNotFoundException.class,
            () -> bookingController.confirmBooking("UNKNOWN", request));
    }

    @Test
    void confirmBooking_BookingException_Rethrown() {
        BookingConfirmRequest request = BookingConfirmRequest.builder()
            .holdToken("HOLD_X").customerId(1L).paymentId("PAY_123").build();

        when(bookingService.confirmBooking(any()))
            .thenThrow(new BookingException("Hold expired"));

        assertThrows(BookingException.class,
            () -> bookingController.confirmBooking("HOLD_X", request));
    }

    // ─── cancelSeatHold ─────────────────────────────────────────────────

    @Test
    void cancelSeatHold_Success_Returns204() {
        doNothing().when(bookingService).cancelSeatHold("HOLD_ABC", 1L);

        ResponseEntity<Void> result = bookingController.cancelSeatHold("HOLD_ABC", 1L);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
    }

    @Test
    void cancelSeatHold_NotFound_Rethrown() {
        doThrow(new BookingNotFoundException("Not found"))
            .when(bookingService).cancelSeatHold("UNKNOWN", 1L);

        assertThrows(BookingNotFoundException.class,
            () -> bookingController.cancelSeatHold("UNKNOWN", 1L));
    }

    @Test
    void cancelSeatHold_BookingException_Rethrown() {
        doThrow(new BookingException("Hold is not active"))
            .when(bookingService).cancelSeatHold("HOLD_EXP", 1L);

        assertThrows(BookingException.class,
            () -> bookingController.cancelSeatHold("HOLD_EXP", 1L));
    }

    // ─── getSeatHold ────────────────────────────────────────────────────

    @Test
    void getSeatHold_Found() {
        SeatHoldDto dto = SeatHoldDto.builder()
            .id(1L).holdToken("HOLD_ABC").customerId(1L).eventId(1L)
            .seatIds(List.of(1L)).status("ACTIVE")
            .expiresAt(LocalDateTime.now().plusMinutes(5))
            .createdAt(LocalDateTime.now())
            .build();

        when(bookingService.getSeatHold("HOLD_ABC")).thenReturn(Optional.of(dto));

        ResponseEntity<SeatHoldDto> result = bookingController.getSeatHold("HOLD_ABC");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("HOLD_ABC", result.getBody().getHoldToken());
    }

    @Test
    void getSeatHold_NotFound_ThrowsException() {
        when(bookingService.getSeatHold("MISSING")).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class,
            () -> bookingController.getSeatHold("MISSING"));
    }

    // ─── getBooking ─────────────────────────────────────────────────────

    @Test
    void getBooking_Found() {
        BookingDto dto = BookingDto.builder()
            .id(1L).bookingReference("BK-001").customerId(1L)
            .status("CONFIRMED").build();

        when(bookingService.getBookingByReference("BK-001")).thenReturn(Optional.of(dto));

        ResponseEntity<BookingDto> result = bookingController.getBooking("BK-001");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("BK-001", result.getBody().getBookingReference());
    }

    @Test
    void getBooking_NotFound_ThrowsException() {
        when(bookingService.getBookingByReference("UNKNOWN")).thenReturn(Optional.empty());

        assertThrows(BookingNotFoundException.class,
            () -> bookingController.getBooking("UNKNOWN"));
    }

    // ─── health ─────────────────────────────────────────────────────────

    @Test
    void health_Returns200() {
        ResponseEntity<String> result = bookingController.health();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("Booking Service is healthy", result.getBody());
    }
}
