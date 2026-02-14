package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.entity.SeatHold;
import com.ticketing.common.service.SeatStatusCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Kafka consumer for seat state transitions.
 * Handles DB updates when seat holds expire via Redis TTL.
 *
 * The keyspace notification listener (DefaultSeatHoldExpiryService) publishes
 * lightweight events here. This consumer does the heavier DB work with proper
 * retry semantics provided by Kafka consumer groups.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeatStateConsumer {

    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final EventMessagingService messagingService;
    private final ObjectMapper objectMapper;
    private final SeatStatusCacheService seatStatusCacheService;

    @Transactional
    @KafkaListener(
        topics = "${kafka.topics.seat-state-transitions:seat-state-transitions}",
        groupId = "${kafka.consumer.group-id:booking-service-seat-state}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onSeatStateTransition(ConsumerRecord<String, String> record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(record.value(), Map.class);

            String eventType = (String) event.get("eventType");

            if ("SEAT_HOLD_EXPIRED".equals(eventType)) {
                Long eventId = ((Number) event.get("eventId")).longValue();
                Long seatId = ((Number) event.get("seatId")).longValue();

                handleSeatExpiry(eventId, seatId);
            } else {
                log.debug("Ignoring event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Failed to process seat state transition: key={}", record.key(), e);
        }
    }

    private void handleSeatExpiry(Long eventId, Long seatId) {
        log.info("Processing seat expiry: eventId={} seatId={}", eventId, seatId);

        // Release the seat back to AVAILABLE (only if currently HELD)
        int released = seatRepository.releaseSeats(Collections.singletonList(seatId));

        if (released == 0) {
            // Seat was already released, confirmed, or in another state. Nothing to do.
            log.debug("Seat {} already released or booked, skipping", seatId);
            return;
        }

        // Cache seat status transition: HELD → AVAILABLE
        seatStatusCacheService.transitionSeatStatus(eventId, seatId, "HELD", "AVAILABLE");

        // Find the active hold that references this seat and mark it expired
        List<SeatHold> activeHolds = seatHoldRepository.findExpiredHoldsForSeat(eventId, seatId, LocalDateTime.now());

        for (SeatHold hold : activeHolds) {
            hold.expire();
            seatHoldRepository.save(hold);

            messagingService.publishSeatHoldExpired(
                hold.getHoldToken(),
                hold.getCustomerId(),
                eventId,
                hold.getSeatIds()
            );

            log.info("Expired hold: token={} customer={} eventId={}", 
                    hold.getHoldToken(), hold.getCustomerId(), eventId);
        }

        log.info("Seat released via TTL expiry: eventId={} seatId={}", eventId, seatId);
    }
}
