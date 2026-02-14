package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Listens to Redis keyspace notifications for expired seat hold keys.
 * On expiry, publishes a lightweight event to Kafka so the consumer
 * can handle the DB cleanup with proper retry and error handling.
 */
@Service
@Slf4j
public class DefaultSeatHoldExpiryService implements SeatHoldExpiryService {

    private final RedisMessageListenerContainer listenerContainer;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Key prefix: seat:{eventId}:{seatId}:HELD
    private static final String SEAT_KEY_PREFIX = "seat:";
    private static final String HELD_SUFFIX = ":HELD";

    @Value("${kafka.topics.seat-state-transitions:seat-state-transitions}")
    private String seatStateTransitionsTopic;

    public DefaultSeatHoldExpiryService(
            RedisMessageListenerContainer listenerContainer,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(
            this,
            new PatternTopic("__keyevent@0__:expired")
        );
        log.info("Redis keyspace notification listener registered for expired keys");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());

        if (!expiredKey.startsWith(SEAT_KEY_PREFIX) || !expiredKey.endsWith(HELD_SUFFIX)) {
            return;
        }

        // Parse seat:{eventId}:{seatId}:HELD
        try {
            String[] parts = expiredKey.split(":");
            if (parts.length != 4) {
                log.warn("Unexpected key format: {}", expiredKey);
                return;
            }

            Long eventId = Long.parseLong(parts[1]);
            Long seatId = Long.parseLong(parts[2]);

            log.info("Seat hold expired: eventId={} seatId={}", eventId, seatId);

            publishExpiryEvent(eventId, seatId);

        } catch (NumberFormatException e) {
            log.warn("Could not parse seat key: {}", expiredKey, e);
        }
    }

    private void publishExpiryEvent(Long eventId, Long seatId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "SEAT_HOLD_EXPIRED");
            event.put("eventId", eventId);
            event.put("seatId", seatId);
            event.put("timestamp", System.currentTimeMillis());
            event.put("source", "redis-ttl");

            String json = objectMapper.writeValueAsString(event);
            String kafkaKey = eventId + ":" + seatId;

            kafkaTemplate.send(seatStateTransitionsTopic, kafkaKey, json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish seat expiry event: eventId={} seatId={}", eventId, seatId, ex);
                    } else {
                        log.debug("Published seat expiry event: eventId={} seatId={} partition={}",
                                eventId, seatId, result.getRecordMetadata().partition());
                    }
                });

        } catch (Exception e) {
            log.error("Error creating seat expiry event: eventId={} seatId={}", eventId, seatId, e);
        }
    }

    // These methods are kept for the safety-net cleanup job
    @Override
    public void handleExpiredSeatHold(String holdToken) {
        // No longer used directly -- expiry is handled via Kafka consumer
        log.debug("handleExpiredSeatHold called for: {} (delegated to Kafka)", holdToken);
    }

    @Override
    public void cleanupExpiredHolds() {
        // Delegated to SeatHoldCleanupJob which reconciles Redis vs DB
        log.debug("cleanupExpiredHolds called (delegated to cleanup job)");
    }

    @Override
    public int bulkMarkExpiredHolds() {
        // Delegated to SeatHoldCleanupJob
        log.debug("bulkMarkExpiredHolds called (delegated to cleanup job)");
        return 0;
    }
}
