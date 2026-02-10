package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.dto.BookingDto;
import com.ticketing.common.dto.SeatHoldDto;
import com.ticketing.common.entity.Seat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventMessagingService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.seat-hold-created:seat-hold-created}")
    private String seatHoldCreatedTopic;

    @Value("${kafka.topics.seat-hold-confirmed:seat-hold-confirmed}")
    private String seatHoldConfirmedTopic;

    @Value("${kafka.topics.seat-hold-cancelled:seat-hold-cancelled}")
    private String seatHoldCancelledTopic;

    @Value("${kafka.topics.seat-hold-expired:seat-hold-expired}")
    private String seatHoldExpiredTopic;

    @Value("${kafka.topics.booking-confirmed:booking-confirmed}")
    private String bookingConfirmedTopic;

    /**
     * Publish seat hold created event
     */
    public void publishSeatHoldCreated(SeatHoldDto seatHold, List<Seat> seats) {
        try {
            Map<String, Object> event = createSeatHoldEvent(seatHold, "CREATED");
            event.put("seats", seats.stream().map(this::createSeatInfo).toList());

            String eventJson = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(seatHoldCreatedTopic, seatHold.getHoldToken(), eventJson);

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish seat hold created event: {}", seatHold.getHoldToken(), throwable);
                } else {
                    log.debug("Published seat hold created event: {} to partition: {}",
                             seatHold.getHoldToken(), result.getRecordMetadata().partition());
                }
            });

        } catch (Exception e) {
            log.error("Error creating seat hold created event: {}", seatHold.getHoldToken(), e);
        }
    }

    /**
     * Publish seat hold confirmed event
     */
    public void publishSeatHoldConfirmed(SeatHoldDto seatHold) {
        try {
            Map<String, Object> event = createSeatHoldEvent(seatHold, "CONFIRMED");
            String eventJson = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(seatHoldConfirmedTopic, seatHold.getHoldToken(), eventJson);

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish seat hold confirmed event: {}", seatHold.getHoldToken(), throwable);
                } else {
                    log.debug("Published seat hold confirmed event: {}", seatHold.getHoldToken());
                }
            });

        } catch (Exception e) {
            log.error("Error creating seat hold confirmed event: {}", seatHold.getHoldToken(), e);
        }
    }

    /**
     * Publish seat hold cancelled event
     */
    public void publishSeatHoldCancelled(SeatHoldDto seatHold) {
        try {
            Map<String, Object> event = createSeatHoldEvent(seatHold, "CANCELLED");
            String eventJson = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(seatHoldCancelledTopic, seatHold.getHoldToken(), eventJson);

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish seat hold cancelled event: {}", seatHold.getHoldToken(), throwable);
                } else {
                    log.debug("Published seat hold cancelled event: {}", seatHold.getHoldToken());
                }
            });

        } catch (Exception e) {
            log.error("Error creating seat hold cancelled event: {}", seatHold.getHoldToken(), e);
        }
    }

    /**
     * Publish seat hold expired event (triggered by Redis TTL)
     */
    public void publishSeatHoldExpired(String holdToken, Long customerId, Long eventId, List<Long> seatIds) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "SEAT_HOLD_EXPIRED");
            event.put("holdToken", holdToken);
            event.put("customerId", customerId);
            event.put("eventId", eventId);
            event.put("seatIds", seatIds);
            event.put("timestamp", System.currentTimeMillis());
            event.put("source", "booking-service");

            String eventJson = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(seatHoldExpiredTopic, holdToken, eventJson);

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish seat hold expired event: {}", holdToken, throwable);
                } else {
                    log.debug("Published seat hold expired event: {}", holdToken);
                }
            });

        } catch (Exception e) {
            log.error("Error creating seat hold expired event: {}", holdToken, e);
        }
    }

    /**
     * Publish booking confirmed event
     */
    public void publishBookingConfirmed(BookingDto booking) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "BOOKING_CONFIRMED");
            event.put("bookingId", booking.getId());
            event.put("bookingReference", booking.getBookingReference());
            event.put("customerId", booking.getCustomerId());
            event.put("eventId", booking.getEventId());
            event.put("seatIds", booking.getSeatIds());
            event.put("totalAmount", booking.getTotalAmount());
            event.put("paymentId", booking.getPaymentId());
            event.put("holdToken", booking.getHoldToken());
            event.put("confirmedAt", booking.getConfirmedAt());
            event.put("timestamp", System.currentTimeMillis());
            event.put("source", "booking-service");

            String eventJson = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(bookingConfirmedTopic, booking.getBookingReference(), eventJson);

            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Failed to publish booking confirmed event: {}", booking.getBookingReference(), throwable);
                } else {
                    log.debug("Published booking confirmed event: {}", booking.getBookingReference());
                }
            });

        } catch (Exception e) {
            log.error("Error creating booking confirmed event: {}", booking.getBookingReference(), e);
        }
    }

    private Map<String, Object> createSeatHoldEvent(SeatHoldDto seatHold, String eventType) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "SEAT_HOLD_" + eventType);
        event.put("holdToken", seatHold.getHoldToken());
        event.put("customerId", seatHold.getCustomerId());
        event.put("eventId", seatHold.getEventId());
        event.put("seatIds", seatHold.getSeatIds());
        event.put("seatCount", seatHold.getSeatCount());
        event.put("expiresAt", seatHold.getExpiresAt());
        event.put("status", seatHold.getStatus());
        event.put("createdAt", seatHold.getCreatedAt());
        event.put("timestamp", System.currentTimeMillis());
        event.put("source", "booking-service");
        return event;
    }

    private Map<String, Object> createSeatInfo(Seat seat) {
        Map<String, Object> seatInfo = new HashMap<>();
        seatInfo.put("seatId", seat.getId());
        seatInfo.put("section", seat.getSection());
        seatInfo.put("rowLetter", seat.getRowLetter());
        seatInfo.put("seatNumber", seat.getSeatNumber());
        seatInfo.put("price", seat.getPrice());
        seatInfo.put("seatIdentifier", seat.getSeatIdentifier());
        return seatInfo;
    }
}
