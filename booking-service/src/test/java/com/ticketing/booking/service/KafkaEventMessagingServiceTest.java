package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketing.common.dto.BookingDto;
import com.ticketing.common.dto.SeatHoldDto;
import com.ticketing.common.entity.Seat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaEventMessagingServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaEventMessagingService messagingService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        messagingService = new KafkaEventMessagingService(kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(messagingService, "seatHoldCreatedTopic", "seat-hold-created");
        ReflectionTestUtils.setField(messagingService, "seatHoldConfirmedTopic", "seat-hold-confirmed");
        ReflectionTestUtils.setField(messagingService, "seatHoldCancelledTopic", "seat-hold-cancelled");
        ReflectionTestUtils.setField(messagingService, "seatHoldExpiredTopic", "seat-hold-expired");
        ReflectionTestUtils.setField(messagingService, "bookingConfirmedTopic", "booking-confirmed");
    }

    // ─── Helper methods ──────────────────────────────────────────────────

    private SeatHoldDto sampleHoldDto() {
        return SeatHoldDto.builder()
            .id(1L)
            .holdToken("HOLD_ABC")
            .customerId(100L)
            .eventId(1L)
            .seatIds(List.of(10L, 11L))
            .status("ACTIVE")
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .createdAt(LocalDateTime.now())
            .build();
    }

    private BookingDto sampleBookingDto() {
        return BookingDto.builder()
            .id(1L)
            .bookingReference("BK-001")
            .customerId(100L)
            .eventId(1L)
            .seatIds(List.of(10L, 11L))
            .totalAmount(new BigDecimal("100.00"))
            .status("CONFIRMED")
            .paymentId("PAY_123")
            .holdToken("HOLD_ABC")
            .confirmedAt(LocalDateTime.now())
            .build();
    }

    private List<Seat> sampleSeats() {
        Seat seat1 = Seat.builder()
            .id(10L).section("VIP").rowLetter("A").seatNumber(1)
            .price(new BigDecimal("50.00")).status(Seat.SeatStatus.HELD).build();
        Seat seat2 = Seat.builder()
            .id(11L).section("VIP").rowLetter("A").seatNumber(2)
            .price(new BigDecimal("50.00")).status(Seat.SeatStatus.HELD).build();
        return List.of(seat1, seat2);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> successFuture() {
        org.apache.kafka.clients.producer.RecordMetadata metadata = 
            new org.apache.kafka.clients.producer.RecordMetadata(
                new org.apache.kafka.common.TopicPartition("test-topic", 0),
                0L, 0L, 0L, 0L, 0, 0
            );

        org.apache.kafka.clients.producer.ProducerRecord<String, String> producerRecord = 
            new org.apache.kafka.clients.producer.ProducerRecord<>("topic", "key", "value");
        SendResult<String, String> sendResult = new SendResult<>(producerRecord, metadata);

        return CompletableFuture.completedFuture(sendResult);
    }

    private CompletableFuture<SendResult<String, String>> failureFuture() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka send failed"));
        return future;
    }

    // ─── publishSeatHoldCreated ──────────────────────────────────────────

    @Test
    void publishSeatHoldCreated_Success() {
        when(kafkaTemplate.send(eq("seat-hold-created"), eq("HOLD_ABC"), anyString()))
            .thenReturn(successFuture());

        messagingService.publishSeatHoldCreated(sampleHoldDto(), sampleSeats());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("seat-hold-created"), eq("HOLD_ABC"), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("SEAT_HOLD_CREATED"));
        assertTrue(json.contains("HOLD_ABC"));
        assertTrue(json.contains("seats"));
    }

    @Test
    void publishSeatHoldCreated_KafkaSendFails_LogsError() {
        when(kafkaTemplate.send(eq("seat-hold-created"), eq("HOLD_ABC"), anyString()))
            .thenReturn(failureFuture());

        // Should not throw — logs the error
        assertDoesNotThrow(() -> messagingService.publishSeatHoldCreated(sampleHoldDto(), sampleSeats()));
        verify(kafkaTemplate).send(eq("seat-hold-created"), eq("HOLD_ABC"), anyString());
    }

    @Test
    void publishSeatHoldCreated_SerializationError_CaughtGracefully() {
        // Use a broken ObjectMapper to force serialization error
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        KafkaEventMessagingService brokenService = new KafkaEventMessagingService(kafkaTemplate, brokenMapper);
        ReflectionTestUtils.setField(brokenService, "seatHoldCreatedTopic", "seat-hold-created");

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Broken") {});
        } catch (Exception ignored) {}

        assertDoesNotThrow(() -> brokenService.publishSeatHoldCreated(sampleHoldDto(), sampleSeats()));
        verifyNoInteractions(kafkaTemplate);
    }

    // ─── publishSeatHoldConfirmed ────────────────────────────────────────

    @Test
    void publishSeatHoldConfirmed_Success() {
        when(kafkaTemplate.send(eq("seat-hold-confirmed"), eq("HOLD_ABC"), anyString()))
            .thenReturn(successFuture());

        messagingService.publishSeatHoldConfirmed(sampleHoldDto());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("seat-hold-confirmed"), eq("HOLD_ABC"), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("SEAT_HOLD_CONFIRMED"));
    }

    @Test
    void publishSeatHoldConfirmed_KafkaFails_LogsError() {
        when(kafkaTemplate.send(eq("seat-hold-confirmed"), eq("HOLD_ABC"), anyString()))
            .thenReturn(failureFuture());

        assertDoesNotThrow(() -> messagingService.publishSeatHoldConfirmed(sampleHoldDto()));
    }

    @Test
    void publishSeatHoldConfirmed_SerializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        KafkaEventMessagingService brokenService = new KafkaEventMessagingService(kafkaTemplate, brokenMapper);
        ReflectionTestUtils.setField(brokenService, "seatHoldConfirmedTopic", "seat-hold-confirmed");

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Broken") {});
        } catch (Exception ignored) {}

        assertDoesNotThrow(() -> brokenService.publishSeatHoldConfirmed(sampleHoldDto()));
    }

    // ─── publishSeatHoldCancelled ────────────────────────────────────────

    @Test
    void publishSeatHoldCancelled_Success() {
        when(kafkaTemplate.send(eq("seat-hold-cancelled"), eq("HOLD_ABC"), anyString()))
            .thenReturn(successFuture());

        messagingService.publishSeatHoldCancelled(sampleHoldDto());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("seat-hold-cancelled"), eq("HOLD_ABC"), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("SEAT_HOLD_CANCELLED"));
    }

    @Test
    void publishSeatHoldCancelled_KafkaFails_LogsError() {
        when(kafkaTemplate.send(eq("seat-hold-cancelled"), eq("HOLD_ABC"), anyString()))
            .thenReturn(failureFuture());

        assertDoesNotThrow(() -> messagingService.publishSeatHoldCancelled(sampleHoldDto()));
    }

    @Test
    void publishSeatHoldCancelled_SerializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        KafkaEventMessagingService brokenService = new KafkaEventMessagingService(kafkaTemplate, brokenMapper);
        ReflectionTestUtils.setField(brokenService, "seatHoldCancelledTopic", "seat-hold-cancelled");

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Broken") {});
        } catch (Exception ignored) {}

        assertDoesNotThrow(() -> brokenService.publishSeatHoldCancelled(sampleHoldDto()));
    }

    // ─── publishSeatHoldExpired ──────────────────────────────────────────

    @Test
    void publishSeatHoldExpired_Success() {
        when(kafkaTemplate.send(eq("seat-hold-expired"), eq("HOLD_ABC"), anyString()))
            .thenReturn(successFuture());

        messagingService.publishSeatHoldExpired("HOLD_ABC", 100L, 1L, List.of(10L, 11L));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("seat-hold-expired"), eq("HOLD_ABC"), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("SEAT_HOLD_EXPIRED"));
        assertTrue(jsonCaptor.getValue().contains("HOLD_ABC"));
    }

    @Test
    void publishSeatHoldExpired_KafkaFails_LogsError() {
        when(kafkaTemplate.send(eq("seat-hold-expired"), eq("HOLD_ABC"), anyString()))
            .thenReturn(failureFuture());

        assertDoesNotThrow(() -> messagingService.publishSeatHoldExpired("HOLD_ABC", 100L, 1L, List.of(10L)));
    }

    @Test
    void publishSeatHoldExpired_SerializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        KafkaEventMessagingService brokenService = new KafkaEventMessagingService(kafkaTemplate, brokenMapper);
        ReflectionTestUtils.setField(brokenService, "seatHoldExpiredTopic", "seat-hold-expired");

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Broken") {});
        } catch (Exception ignored) {}

        assertDoesNotThrow(() -> brokenService.publishSeatHoldExpired("HOLD_ABC", 100L, 1L, List.of(10L)));
    }

    // ─── publishBookingConfirmed ─────────────────────────────────────────

    @Test
    void publishBookingConfirmed_Success() {
        when(kafkaTemplate.send(eq("booking-confirmed"), eq("BK-001"), anyString()))
            .thenReturn(successFuture());

        messagingService.publishBookingConfirmed(sampleBookingDto());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("booking-confirmed"), eq("BK-001"), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("BOOKING_CONFIRMED"));
        assertTrue(json.contains("BK-001"));
        assertTrue(json.contains("PAY_123"));
    }

    @Test
    void publishBookingConfirmed_KafkaFails_LogsError() {
        when(kafkaTemplate.send(eq("booking-confirmed"), eq("BK-001"), anyString()))
            .thenReturn(failureFuture());

        assertDoesNotThrow(() -> messagingService.publishBookingConfirmed(sampleBookingDto()));
    }

    @Test
    void publishBookingConfirmed_SerializationError() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        KafkaEventMessagingService brokenService = new KafkaEventMessagingService(kafkaTemplate, brokenMapper);
        ReflectionTestUtils.setField(brokenService, "bookingConfirmedTopic", "booking-confirmed");

        try {
            when(brokenMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Broken") {});
        } catch (Exception ignored) {}

        assertDoesNotThrow(() -> brokenService.publishBookingConfirmed(sampleBookingDto()));
    }
}
