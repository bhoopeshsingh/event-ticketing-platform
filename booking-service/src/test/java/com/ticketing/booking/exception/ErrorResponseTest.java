package com.ticketing.booking.exception;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorResponseTest {

    @Test
    void testErrorResponseBuilderAndGetters() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> validationErrors = Map.of("field", "error");

        ErrorResponse response = ErrorResponse.builder()
            .timestamp(now)
            .status(400)
            .error("Bad Request")
            .message("Invalid input")
            .path("/api/test")
            .validationErrors(validationErrors)
            .traceId("trace-123")
            .build();

        assertEquals(now, response.getTimestamp());
        assertEquals(400, response.getStatus());
        assertEquals("Bad Request", response.getError());
        assertEquals("Invalid input", response.getMessage());
        assertEquals("/api/test", response.getPath());
        assertEquals(validationErrors, response.getValidationErrors());
        assertEquals("trace-123", response.getTraceId());
    }

    @Test
    void testErrorResponseSetters() {
        ErrorResponse response = new ErrorResponse();
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> validationErrors = Map.of("field", "error");

        response.setTimestamp(now);
        response.setStatus(500);
        response.setError("Internal Error");
        response.setMessage("Something went wrong");
        response.setPath("/api/error");
        response.setValidationErrors(validationErrors);
        response.setTraceId("trace-456");

        assertEquals(now, response.getTimestamp());
        assertEquals(500, response.getStatus());
        assertEquals("Internal Error", response.getError());
        assertEquals("Something went wrong", response.getMessage());
        assertEquals("/api/error", response.getPath());
        assertEquals(validationErrors, response.getValidationErrors());
        assertEquals("trace-456", response.getTraceId());
    }

    @Test
    void testErrorResponseConstructors() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> validationErrors = Map.of("field", "error");

        // AllArgsConstructor
        ErrorResponse response1 = new ErrorResponse(now, 404, "Not Found", "Missing", "/api/missing", validationErrors, "trace-789");
        assertEquals(404, response1.getStatus());

        // NoArgsConstructor
        ErrorResponse response2 = new ErrorResponse();
        assertNull(response2.getTimestamp());
        assertEquals(0, response2.getStatus());
    }

    @Test
    void testEqualsAndHashCode() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> errors = Map.of("k", "v");
        
        ErrorResponse r1 = ErrorResponse.builder()
            .timestamp(now)
            .status(400)
            .error("E")
            .message("M")
            .path("P")
            .validationErrors(errors)
            .traceId("T")
            .build();
            
        ErrorResponse r2 = ErrorResponse.builder()
            .timestamp(now)
            .status(400)
            .error("E")
            .message("M")
            .path("P")
            .validationErrors(errors)
            .traceId("T")
            .build();

        // Self equality
        assertEquals(r1, r1);
        
        // Logical equality
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());

        // Not equal to null or other type
        assertNotEquals(r1, null);
        assertNotEquals(r1, new Object());

        // Field variance
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now.plusSeconds(1)).status(400).error("E").message("M").path("P").validationErrors(errors).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(500).error("E").message("M").path("P").validationErrors(errors).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(400).error("X").message("M").path("P").validationErrors(errors).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(400).error("E").message("X").path("P").validationErrors(errors).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(400).error("E").message("M").path("X").validationErrors(errors).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(400).error("E").message("M").path("P").validationErrors(Map.of()).traceId("T").build());
        assertNotEquals(r1, ErrorResponse.builder().timestamp(now).status(400).error("E").message("M").path("P").validationErrors(errors).traceId("X").build());
        
        // Null fields vs Non-null fields
        ErrorResponse allNull = new ErrorResponse();
        assertNotEquals(r1, allNull);
        assertNotEquals(allNull, r1);
        assertEquals(allNull, new ErrorResponse());
        assertEquals(allNull.hashCode(), new ErrorResponse().hashCode());
    }

    @Test
    void testToString() {
        LocalDateTime now = LocalDateTime.now();
        ErrorResponse response = ErrorResponse.builder()
            .timestamp(now)
            .status(400)
            .error("Error")
            .message("Msg")
            .path("/path")
            .validationErrors(Map.of("f", "e"))
            .traceId("123")
            .build();
            
        String stringRep = response.toString();
        assertTrue(stringRep.contains("ErrorResponse"));
        assertTrue(stringRep.contains("400"));
        assertTrue(stringRep.contains("Error"));
        assertTrue(stringRep.contains("Msg"));
        assertTrue(stringRep.contains("/path"));
        assertTrue(stringRep.contains("123"));
        assertTrue(stringRep.contains("f=e"));
    }
    @Test
    void testBuilderToString() {
        String builderString = ErrorResponse.builder().status(200).toString();
        assertNotNull(builderString);
        assertTrue(builderString.contains("ErrorResponseBuilder"));
        assertTrue(builderString.contains("status=200"));
    }

    @Test
    void testCanEqual() {
        ErrorResponse r1 = new ErrorResponse();
        ErrorResponse r2 = new ErrorResponse();
        assertTrue(r1.canEqual(r2));
        assertFalse(r1.canEqual(new Object()));
    }
}
