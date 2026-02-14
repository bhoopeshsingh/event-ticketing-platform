package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.entity.SeatHold;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Safety-net cleanup job that reconciles Redis vs DB state.
 * Runs periodically to catch any expired holds that were missed by
 * the Redis keyspace notification -> Kafka -> consumer pipeline.
 *
 * This handles edge cases like:
 * - Redis restart causing missed keyspace notifications
 * - Kafka consumer downtime
 * - Network partitions between Redis and the application
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "booking.hold.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class SeatHoldCleanupJob {

    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final EventMessagingService messagingService;
    private final StringRedisTemplate redisTemplate;

    /**
     * Runs every 60 seconds. Finds holds that are ACTIVE in DB but past their expiry time,
     * verifies the Redis key is actually gone, and cleans up DB state.
     */
    @Scheduled(fixedDelayString = "${booking.hold.cleanup.interval.ms:60000}")
    @Transactional
    public void reconcileExpiredHolds() {
        List<SeatHold> expiredHolds = seatHoldRepository.findExpiredHolds(LocalDateTime.now());

        if (expiredHolds.isEmpty()) {
            return;
        }

        log.info("Safety-net cleanup: found {} expired holds to reconcile", expiredHolds.size());

        int cleaned = 0;
        for (SeatHold hold : expiredHolds) {
            try {
                if (reconcileHold(hold)) {
                    cleaned++;
                }
            } catch (Exception e) {
                log.error("Failed to reconcile hold: {}", hold.getHoldToken(), e);
            }
        }

        if (cleaned > 0) {
            log.info("Safety-net cleanup: reconciled {} expired holds", cleaned);
        }
    }

    private boolean reconcileHold(SeatHold hold) {
        Long eventId = hold.getEvent().getId();

        // Check if any Redis key still exists for this hold's seats.
        // If it does, the hold hasn't truly expired yet (clock skew or TTL not yet reached).
        String expectedValue = hold.getCustomerId() + ":" + hold.getHoldToken();
        boolean anyKeyExists = false;

        for (Long seatId : hold.getSeatIds()) {
            String key = BookingService.seatHoldKey(eventId, seatId);
            String stored = redisTemplate.opsForValue().get(key);
            if (expectedValue.equals(stored)) {
                anyKeyExists = true;
                break;
            }
        }

        if (anyKeyExists) {
            log.debug("Hold {} still has Redis keys, skipping", hold.getHoldToken());
            return false;
        }

        // Redis keys are gone. Clean up DB state.
        int released = seatRepository.releaseSeats(hold.getSeatIds());
        hold.expire();
        seatHoldRepository.save(hold);

        messagingService.publishSeatHoldExpired(
            hold.getHoldToken(),
            hold.getCustomerId(),
            eventId,
            hold.getSeatIds()
        );

        log.info("Safety-net: cleaned up hold {} ({} seats released)", hold.getHoldToken(), released);
        return true;
    }
}
