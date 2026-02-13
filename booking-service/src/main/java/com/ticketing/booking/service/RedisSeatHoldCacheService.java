package com.ticketing.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.dto.SeatHoldDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class RedisSeatHoldCacheService implements SeatHoldCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SEAT_HOLD_PREFIX = "seat_hold:";
    private static final String SEAT_HOLD_INDEX_PREFIX = "seat_hold_index:";
    private static final String CUSTOMER_HOLDS_PREFIX = "customer_holds:";

    @Autowired
    public RedisSeatHoldCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Cache seat hold with TTL matching the hold expiry
     */
    @Override
    public void cacheSeatHold(SeatHoldDto seatHold) {
        try {
            String holdKey = SEAT_HOLD_PREFIX + seatHold.getHoldToken();
            String holdJson = objectMapper.writeValueAsString(seatHold);

            // Calculate TTL until hold expires
            Duration ttl = Duration.between(LocalDateTime.now(), seatHold.getExpiresAt());
            if (ttl.isNegative()) {
                ttl = Duration.ofSeconds(1); // Minimum 1 second TTL
            }

            // Cache the hold
            redisTemplate.opsForValue().set(holdKey, holdJson, ttl);

            // Add to customer index
            String customerKey = CUSTOMER_HOLDS_PREFIX + seatHold.getCustomerId();
            redisTemplate.opsForSet().add(customerKey, seatHold.getHoldToken());
            redisTemplate.expire(customerKey, ttl.plusMinutes(5)); // Keep index a bit longer

            // Index by seat IDs for conflict detection
            for (Long seatId : seatHold.getSeatIds()) {
                String seatIndexKey = SEAT_HOLD_INDEX_PREFIX + seatId;
                redisTemplate.opsForValue().set(seatIndexKey, seatHold.getHoldToken(), ttl);
            }

            log.debug("Cached seat hold: {} with TTL: {} seconds",
                     seatHold.getHoldToken(), ttl.getSeconds());

        } catch (JsonProcessingException e) {
            log.error("Error serializing seat hold: {}", seatHold.getHoldToken(), e);
        } catch (Exception e) {
            log.error("Error caching seat hold: {}", seatHold.getHoldToken(), e);
        }
    }

    /**
     * Get seat hold from cache
     */
    @Override
    public Optional<SeatHoldDto> getSeatHold(String holdToken) {
        try {
            String holdKey = SEAT_HOLD_PREFIX + holdToken;
            String holdJson = redisTemplate.opsForValue().get(holdKey);

            if (holdJson == null) {
                return Optional.empty();
            }

            SeatHoldDto seatHold = objectMapper.readValue(holdJson, SeatHoldDto.class);
            return Optional.of(seatHold);

        } catch (JsonProcessingException e) {
            log.error("Error deserializing seat hold: {}", holdToken, e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error retrieving seat hold from cache: {}", holdToken, e);
            return Optional.empty();
        }
    }

    /**
     * Remove seat hold from cache (on confirmation or cancellation)
     */
    @Override
    public void removeSeatHold(String holdToken) {
        try {
            // Get the hold first to clean up indexes
            Optional<SeatHoldDto> seatHold = getSeatHold(holdToken);
            if (seatHold.isPresent()) {
                SeatHoldDto hold = seatHold.get();

                // Remove from customer index
                String customerKey = CUSTOMER_HOLDS_PREFIX + hold.getCustomerId();
                redisTemplate.opsForSet().remove(customerKey, holdToken);

                // Remove seat indexes
                for (Long seatId : hold.getSeatIds()) {
                    String seatIndexKey = SEAT_HOLD_INDEX_PREFIX + seatId;
                    redisTemplate.delete(seatIndexKey);
                }
            }

            // Remove the main hold
            String holdKey = SEAT_HOLD_PREFIX + holdToken;
            redisTemplate.delete(holdKey);

            log.debug("Removed seat hold from cache: {}", holdToken);

        } catch (Exception e) {
            log.error("Error removing seat hold from cache: {}", holdToken, e);
        }
    }

    /**
     * Check if any seats are already held
     */
    @Override
    public boolean areSeatsHeld(List<Long> seatIds) {
        try {
            for (Long seatId : seatIds) {
                String seatIndexKey = SEAT_HOLD_INDEX_PREFIX + seatId;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(seatIndexKey))) {
                    String holdToken = redisTemplate.opsForValue().get(seatIndexKey);
                    log.debug("Seat {} is held by: {}", seatId, holdToken);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Error checking if seats are held: {}", seatIds, e);
            // Fail safe - assume seats are held if we can't check
            return true;
        }
    }

    /**
     * Get all active holds for a customer
     */
    @Override
    public Set<String> getCustomerHoldTokens(Long customerId) {
        try {
            String customerKey = CUSTOMER_HOLDS_PREFIX + customerId;
            return redisTemplate.opsForSet().members(customerKey);
        } catch (Exception e) {
            log.error("Error getting customer holds: {}", customerId, e);
            return Set.of();
        }
    }

    /**
     * Update hold status in cache
     */
    @Override
    public void updateHoldStatus(String holdToken, String newStatus) {
        try {
            Optional<SeatHoldDto> seatHold = getSeatHold(holdToken);
            if (seatHold.isPresent()) {
                SeatHoldDto hold = seatHold.get();
                hold.setStatus(newStatus);

                // Re-cache with shorter TTL for non-active states
                String holdKey = SEAT_HOLD_PREFIX + holdToken;
                String holdJson = objectMapper.writeValueAsString(hold);
                Duration ttl = "ACTIVE".equals(newStatus) ?
                    Duration.between(LocalDateTime.now(), hold.getExpiresAt()) :
                    Duration.ofMinutes(30); // Keep for audit/debugging

                redisTemplate.opsForValue().set(holdKey, holdJson, ttl);
                log.debug("Updated hold status: {} to {}", holdToken, newStatus);
            }
        } catch (Exception e) {
            log.error("Error updating hold status: {} to {}", holdToken, newStatus, e);
        }
    }

    /**
     * Get TTL for a hold
     */
    @Override
    public Duration getHoldTTL(String holdToken) {
        try {
            String holdKey = SEAT_HOLD_PREFIX + holdToken;
            Long ttlSeconds = redisTemplate.getExpire(holdKey);
            return ttlSeconds != null && ttlSeconds > 0 ?
                Duration.ofSeconds(ttlSeconds) : Duration.ZERO;
        } catch (Exception e) {
            log.error("Error getting hold TTL: {}", holdToken, e);
            return Duration.ZERO;
        }
    }

    /**
     * Extend hold expiry (e.g., during payment processing)
     */
    @Override
    public boolean extendHoldExpiry(String holdToken, Duration additionalTime) {
        try {
            String holdKey = SEAT_HOLD_PREFIX + holdToken;
            return Boolean.TRUE.equals(redisTemplate.expire(holdKey, additionalTime));
        } catch (Exception e) {
            log.error("Error extending hold expiry: {}", holdToken, e);
            return false;
        }
    }
}
