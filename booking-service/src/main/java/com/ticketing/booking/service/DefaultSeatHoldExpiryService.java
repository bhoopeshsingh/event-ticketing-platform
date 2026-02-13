package com.ticketing.booking.service;

import com.ticketing.booking.repository.SeatHoldRepository;
import com.ticketing.booking.repository.SeatRepository;
import com.ticketing.common.entity.SeatHold;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Service
@Slf4j
public class DefaultSeatHoldExpiryService implements SeatHoldExpiryService {

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final EventMessagingService messagingService;
    private final DefaultSeatHoldExpiryService self;

    private static final String SEAT_HOLD_PREFIX = "seat_hold:";
    
    // Explicit constructor with @Lazy on self to break circular dependency
    public DefaultSeatHoldExpiryService(
            RedisMessageListenerContainer redisMessageListenerContainer,
            SeatHoldRepository seatHoldRepository,
            SeatRepository seatRepository,
            EventMessagingService messagingService,
            @Lazy DefaultSeatHoldExpiryService self) {
        this.redisMessageListenerContainer = redisMessageListenerContainer;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.messagingService = messagingService;
        this.self = self;
    }


    @PostConstruct
    public void init() {
        log.info("=== INITIALIZING REDIS KEYSPACE NOTIFICATION LISTENER ===");
        log.info("Redis connection factory: {}", redisMessageListenerContainer.getConnectionFactory());
        
        // Subscribe to Redis keyspace notifications for expired keys
        redisMessageListenerContainer.addMessageListener(
            this,
            new PatternTopic("__keyevent@0__:expired")
        );

        log.info("=== REDIS LISTENER INITIALIZED SUCCESSFULLY ===");
        log.info("Subscribed to pattern: __keyevent@0__:expired");
        log.info("Seat hold prefix: {}", SEAT_HOLD_PREFIX);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        
        log.info("=== RECEIVED REDIS EXPIRY NOTIFICATION ===");
        log.info("Expired key: {}", expiredKey);
        log.info("Pattern: {}", new String(pattern));
        log.info("Message channel: {}", new String(message.getChannel()));

        // Only process seat hold keys
        if (expiredKey.startsWith(SEAT_HOLD_PREFIX)) {
            String holdToken = expiredKey.substring(SEAT_HOLD_PREFIX.length());
            log.info("Processing expired seat hold: {}", holdToken);

            try {
                // Call through self-reference to enable @Transactional proxy
                self.handleExpiredSeatHold(holdToken);
                log.info("=== SUCCESSFULLY PROCESSED EXPIRED HOLD: {} ===", holdToken);
            } catch (Exception e) {
                log.error("=== ERROR PROCESSING EXPIRED SEAT HOLD: {} ===", holdToken, e);
                // Continue processing other expired holds
            }
        } else {
            log.debug("Ignoring non-seat-hold expired key: {}", expiredKey);
        }
    }

    @Transactional
    public void handleExpiredSeatHold(String holdToken) {
        Optional<SeatHold> seatHoldOpt = seatHoldRepository.findByHoldTokenWithLock(holdToken);

        if (seatHoldOpt.isEmpty()) {
            log.debug("Seat hold not found in database: {} (may have been already processed)", holdToken);
            return;
        }

        SeatHold seatHold = seatHoldOpt.get();

        // Double-check if hold is actually expired and active
        if (seatHold.getStatus() != SeatHold.HoldStatus.ACTIVE) {
            log.debug("Seat hold {} is not active (status: {}), skipping expiry processing",
                     holdToken, seatHold.getStatus());
            return;
        }

        if (!seatHold.isExpired()) {
            log.warn("Seat hold {} received expiry notification but is not yet expired. " +
                    "Expires at: {}, Current time: {}",
                    holdToken, seatHold.getExpiresAt(), LocalDateTime.now());
            return;
        }

        try {
            // Release the held seats back to available status
            int releasedSeats = seatRepository.releaseSeats(seatHold.getSeatIds());
            log.info("Released {} seats for expired hold: {}", releasedSeats, holdToken);

            // Update hold status to expired
            seatHold.expire();
            seatHoldRepository.save(seatHold);

            // Publish expiry event for audit trail
            messagingService.publishSeatHoldExpired(
                holdToken,
                seatHold.getCustomerId(),
                seatHold.getEvent().getId(),
                seatHold.getSeatIds()
            );

            log.info("Successfully processed expired seat hold: {} for customer: {} with {} seats",
                    holdToken, seatHold.getCustomerId(), seatHold.getSeatIds().size());

        } catch (Exception e) {
            log.error("Failed to process expired seat hold: {}", holdToken, e);
            // Re-throw to ensure transaction rollback
            throw e;
        }
    }

    /**
     * Manual cleanup method for scheduled cleanup of expired holds
     * This serves as a backup mechanism in case Redis notifications are missed
     */
    @Transactional
    public void cleanupExpiredHolds() {
        log.debug("Starting manual cleanup of expired holds");

        try {
            List<SeatHold> expiredHolds = seatHoldRepository.findExpiredHolds(LocalDateTime.now());

            for (SeatHold expiredHold : expiredHolds) {
                try {
                    handleExpiredSeatHold(expiredHold.getHoldToken());
                } catch (Exception e) {
                    log.error("Error during manual cleanup of expired hold: {}",
                             expiredHold.getHoldToken(), e);
                    // Continue with other holds
                }
            }

            if (!expiredHolds.isEmpty()) {
                log.info("Manual cleanup processed {} expired holds", expiredHolds.size());
            }

        } catch (Exception e) {
            log.error("Error during manual expired holds cleanup", e);
        }
    }

    /**
     * Bulk update method for marking expired holds
     */
    @Transactional
    public int bulkMarkExpiredHolds() {
        try {
            int updatedCount = seatHoldRepository.markExpiredHolds(LocalDateTime.now());
            if (updatedCount > 0) {
                log.info("Bulk marked {} holds as expired", updatedCount);
            }
            return updatedCount;
        } catch (Exception e) {
            log.error("Error during bulk mark expired holds", e);
            return 0;
        }
    }
}
