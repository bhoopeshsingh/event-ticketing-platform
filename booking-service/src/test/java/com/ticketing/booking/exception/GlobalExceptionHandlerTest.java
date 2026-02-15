package com.ticketing.booking.exception;

import com.ticketing.booking.service.BookingException;
import com.ticketing.booking.service.BookingNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleBookingNotFoundException() {
        BookingNotFoundException ex = new BookingNotFoundException("Not found: HOLD_123");

        ResponseEntity<ErrorResponse> response = handler.handleBookingNotFoundException(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getStatus());
        assertTrue(response.getBody().getMessage().contains("Not found"));
    }

    @Test
    void handleBookingException_Expired() {
        BookingException ex = new BookingException("Hold expired");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(410, response.getBody().getStatus());
    }

    @Test
    void handleBookingException_Conflict() {
        BookingException ex = new BookingException("Seat already held by another customer");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void handleBookingException_Unavailable() {
        BookingException ex = new BookingException("Seats unavailable");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void handleBookingException_Invalid() {
        BookingException ex = new BookingException("Invalid customer for this hold");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void handleBookingException_AlreadyBooked() {
        BookingException ex = new BookingException("Seat already booked");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void handleBookingException_CannotProcess() {
        BookingException ex = new BookingException("Cannot cancel hold");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void handleBookingException_Timeout() {
        BookingException ex = new BookingException("Session timeout");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(410, response.getBody().getStatus());
    }

    @Test
    void handleBookingException_ExplicitConflict() {
        BookingException ex = new BookingException("Data conflict occurred");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().getStatus());
    }

    @Test
    void handleBookingException_NotAvailable() {
        BookingException ex = new BookingException("Resource not available");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().getStatus());
    }

    @Test
    void handleBookingException_Default() {
        BookingException ex = new BookingException("Some generic error");

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void handleValidationException() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "field1", "must not be blank");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody().getValidationErrors());
        assertEquals("must not be blank", response.getBody().getValidationErrors().get("field1"));
    }

    @Test
    void handleRuntimeException() {
        RuntimeException ex = new RuntimeException("Unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleRuntimeException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void handleGenericException() {
        Exception ex = new Exception("Unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
    @Test
    void handleBookingException_NullMessage() {
        BookingException ex = new BookingException(null);

        ResponseEntity<ErrorResponse> response = handler.handleBookingException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().getStatus());
    }

    @Test
    void handleValidationException_NoErrors() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().getValidationErrors().isEmpty());
    }
}
