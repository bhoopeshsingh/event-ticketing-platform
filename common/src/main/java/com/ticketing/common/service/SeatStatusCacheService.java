package com.ticketing.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis-based seat status cache using sliding window approach.
 * Maintains recent changes (last 2 minutes) per event for real-time status merging.
 * 
 * Key pattern: {eventId}:recent_changes:{status}
 * Data structure: ZSET (sorted set) with timestamp scores for auto-pruning
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeatStatusCacheService {

    private final StringRedisTemplate redisTemplate;

    private static final String RECENT_CHANGES_PREFIX = "%d:recent_changes:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(120);
    private static final long SLIDING_WINDOW_SECONDS = 120;

    /**
     * Cache seat status change with timestamp-based sliding window.
     * Automatically prunes entries older than 2 minutes.
     */
    public void cacheSeatStatusChange(Long eventId, Long seatId, String newStatus) {
        try {
            String key = String.format(RECENT_CHANGES_PREFIX + newStatus, eventId);
            double timestamp = System.currentTimeMillis() / 1000.0;
            
            // Add to ZSET with timestamp score
            redisTemplate.opsForZSet().add(key, seatId.toString(), timestamp);
            
            // Prune old entries (older than 120 seconds)
            double cutoffTime = timestamp - SLIDING_WINDOW_SECONDS;
            redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffTime);
            
            // Refresh TTL
            redisTemplate.expire(key, CACHE_TTL);
            
            log.debug("Cached seat status: eventId={} seatId={} status={}", eventId, seatId, newStatus);
            
        } catch (Exception e) {
            log.error("Failed to cache seat status: eventId={} seatId={} status={}", 
                     eventId, seatId, newStatus, e);
        }
    }

    /**
     * Cache multiple seat status changes in a batch (optimized).
     */
    public void cacheSeatStatusChanges(Long eventId, List<Long> seatIds, String newStatus) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }

        try {
            String key = String.format(RECENT_CHANGES_PREFIX + newStatus, eventId);
            double timestamp = System.currentTimeMillis() / 1000.0;
            
            // Batch add to ZSET
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples = 
                seatIds.stream()
                    .map(seatId -> org.springframework.data.redis.core.ZSetOperations.TypedTuple.of(
                        seatId.toString(), timestamp))
                    .collect(Collectors.toSet());
            
            redisTemplate.opsForZSet().add(key, tuples);
            
            // Prune old entries
            double cutoffTime = timestamp - SLIDING_WINDOW_SECONDS;
            redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffTime);
            
            // Refresh TTL
            redisTemplate.expire(key, CACHE_TTL);
            
            log.debug("Cached {} seat status changes: eventId={} status={}", 
                     seatIds.size(), eventId, newStatus);
            
        } catch (Exception e) {
            log.error("Failed to batch cache seat status: eventId={} seatIds={} status={}", 
                     eventId, seatIds, newStatus, e);
        }
    }

    /**
     * Remove seat from old status set when transitioning to new status.
     * e.g., seat goes HELD → BOOKED, remove from HELD set.
     */
    public void removeSeatFromStatus(Long eventId, Long seatId, String oldStatus) {
        try {
            String key = String.format(RECENT_CHANGES_PREFIX + oldStatus, eventId);
            redisTemplate.opsForZSet().remove(key, seatId.toString());
            
            log.debug("Removed seat from status cache: eventId={} seatId={} oldStatus={}", 
                     eventId, seatId, oldStatus);
            
        } catch (Exception e) {
            log.error("Failed to remove seat from status cache: eventId={} seatId={} oldStatus={}", 
                     eventId, seatId, oldStatus, e);
        }
    }

    /**
     * Handle seat status transition (removes from old, adds to new).
     */
    public void transitionSeatStatus(Long eventId, Long seatId, String fromStatus, String toStatus) {
        if (fromStatus != null && !fromStatus.isEmpty()) {
            removeSeatFromStatus(eventId, seatId, fromStatus);
        }
        cacheSeatStatusChange(eventId, seatId, toStatus);
    }

    /**
     * Batch transition (e.g., multiple seats HELD → BOOKED).
     */
    public void transitionSeatStatuses(Long eventId, List<Long> seatIds, String fromStatus, String toStatus) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }

        try {
            // Remove from old status set
            if (fromStatus != null && !fromStatus.isEmpty()) {
                String oldKey = String.format(RECENT_CHANGES_PREFIX + fromStatus, eventId);
                seatIds.forEach(seatId -> redisTemplate.opsForZSet().remove(oldKey, seatId.toString()));
            }
            
            // Add to new status set
            cacheSeatStatusChanges(eventId, seatIds, toStatus);
            
            log.debug("Transitioned {} seats: eventId={} {} → {}", 
                     seatIds.size(), eventId, fromStatus, toStatus);
            
        } catch (Exception e) {
            log.error("Failed to transition seat statuses: eventId={} {} → {}", 
                     eventId, fromStatus, toStatus, e);
        }
    }

    /**
     * Get all recent seat status changes for an event.
     * Returns map of seatId → status (only seats with recent changes).
     */
    public Map<Long, String> getRecentChanges(Long eventId) {
        Map<Long, String> changes = new HashMap<>();

        try {
            // Get recent HELD seats
            Set<String> heldSeats = redisTemplate.opsForZSet()
                .range(String.format(RECENT_CHANGES_PREFIX + "HELD", eventId), 0, -1);
            if (heldSeats != null) {
                heldSeats.forEach(seatId -> changes.put(Long.parseLong(seatId), "HELD"));
            }

            // Get recent BOOKED seats
            Set<String> bookedSeats = redisTemplate.opsForZSet()
                .range(String.format(RECENT_CHANGES_PREFIX + "BOOKED", eventId), 0, -1);
            if (bookedSeats != null) {
                bookedSeats.forEach(seatId -> changes.put(Long.parseLong(seatId), "BOOKED"));
            }

            // Get recent AVAILABLE seats (cancellations/releases)
            Set<String> availSeats = redisTemplate.opsForZSet()
                .range(String.format(RECENT_CHANGES_PREFIX + "AVAILABLE", eventId), 0, -1);
            if (availSeats != null) {
                availSeats.forEach(seatId -> changes.put(Long.parseLong(seatId), "AVAILABLE"));
            }

            log.debug("Retrieved {} recent seat changes for eventId={}", changes.size(), eventId);

        } catch (Exception e) {
            log.error("Failed to get recent changes for eventId={}", eventId, e);
        }

        return changes;
    }

    /**
     * Get count of seats in each status for an event (from recent changes only).
     */
    public Map<String, Long> getRecentStatusCounts(Long eventId) {
        Map<String, Long> counts = new HashMap<>();

        try {
            counts.put("HELD", redisTemplate.opsForZSet()
                .zCard(String.format(RECENT_CHANGES_PREFIX + "HELD", eventId)));
            counts.put("BOOKED", redisTemplate.opsForZSet()
                .zCard(String.format(RECENT_CHANGES_PREFIX + "BOOKED", eventId)));
            counts.put("AVAILABLE", redisTemplate.opsForZSet()
                .zCard(String.format(RECENT_CHANGES_PREFIX + "AVAILABLE", eventId)));

        } catch (Exception e) {
            log.error("Failed to get recent status counts for eventId={}", eventId, e);
        }

        return counts;
    }

    /**
     * Clear all recent changes for an event (admin operation).
     */
    public void clearRecentChanges(Long eventId) {
        try {
            redisTemplate.delete(String.format(RECENT_CHANGES_PREFIX + "HELD", eventId));
            redisTemplate.delete(String.format(RECENT_CHANGES_PREFIX + "BOOKED", eventId));
            redisTemplate.delete(String.format(RECENT_CHANGES_PREFIX + "AVAILABLE", eventId));
            
            log.info("Cleared recent changes for eventId={}", eventId);
            
        } catch (Exception e) {
            log.error("Failed to clear recent changes for eventId={}", eventId, e);
        }
    }
}
