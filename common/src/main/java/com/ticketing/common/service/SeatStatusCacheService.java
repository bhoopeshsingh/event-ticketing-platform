package com.ticketing.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Redis-based seat status cache for real-time seat browsing.
 *
 * Uses a single HASH per event so each seat can only have ONE status at a time.
 * This eliminates the stale-entry bug where a seat appeared in multiple status
 * groups (e.g. both AVAILABLE and HELD) when statuses cycled within the
 * sliding window.
 *
 * Key:   {eventId}:seat_status   (HASH)
 * Field: seatId (string)
 * Value: status (HELD | BOOKED | AVAILABLE)
 * TTL:   refreshed on every write (configurable, default 10 min)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeatStatusCacheService {

    private final StringRedisTemplate redisTemplate;

    private static final String SEAT_STATUS_KEY = "%d:seat_status";
    private static final Duration CACHE_TTL = Duration.ofSeconds(600);

    /**
     * Set the current status of a single seat.
     * Overwrites any previous status for this seat atomically.
     */
    public void cacheSeatStatusChange(Long eventId, Long seatId, String newStatus) {
        try {
            String key = String.format(SEAT_STATUS_KEY, eventId);
            redisTemplate.opsForHash().put(key, seatId.toString(), newStatus);
            redisTemplate.expire(key, CACHE_TTL);

            log.debug("Cached seat status: eventId={} seatId={} status={}", eventId, seatId, newStatus);
        } catch (Exception e) {
            log.error("Failed to cache seat status: eventId={} seatId={} status={}",
                     eventId, seatId, newStatus, e);
        }
    }

    /**
     * Set the current status of multiple seats in one batch.
     */
    public void cacheSeatStatusChanges(Long eventId, List<Long> seatIds, String newStatus) {
        if (seatIds == null || seatIds.isEmpty()) {
            return;
        }

        try {
            String key = String.format(SEAT_STATUS_KEY, eventId);
            Map<String, String> entries = new HashMap<>();
            for (Long seatId : seatIds) {
                entries.put(seatId.toString(), newStatus);
            }
            redisTemplate.opsForHash().putAll(key, entries);
            redisTemplate.expire(key, CACHE_TTL);

            log.debug("Cached {} seat status changes: eventId={} status={}",
                     seatIds.size(), eventId, newStatus);
        } catch (Exception e) {
            log.error("Failed to batch cache seat status: eventId={} seatIds={} status={}",
                     eventId, seatIds, newStatus, e);
        }
    }

    /**
     * Transition a single seat from one status to another.
     * Since we use a HASH, the old status is implicitly overwritten.
     */
    public void transitionSeatStatus(Long eventId, Long seatId, String fromStatus, String toStatus) {
        cacheSeatStatusChange(eventId, seatId, toStatus);
    }

    /**
     * Transition multiple seats from one status to another.
     */
    public void transitionSeatStatuses(Long eventId, List<Long> seatIds, String fromStatus, String toStatus) {
        cacheSeatStatusChanges(eventId, seatIds, toStatus);
    }

    /**
     * Remove a seat entry from the cache (e.g. when the DB has caught up and
     * the overlay is no longer needed for that seat).
     */
    public void removeSeatFromStatus(Long eventId, Long seatId, String oldStatus) {
        try {
            String key = String.format(SEAT_STATUS_KEY, eventId);
            redisTemplate.opsForHash().delete(key, seatId.toString());

            log.debug("Removed seat from status cache: eventId={} seatId={}", eventId, seatId);
        } catch (Exception e) {
            log.error("Failed to remove seat from status cache: eventId={} seatId={}",
                     eventId, seatId, e);
        }
    }

    /**
     * Get all recent seat status overrides for an event.
     * Returns map of seatId -> status. Each seat appears exactly once.
     */
    public Map<Long, String> getRecentChanges(Long eventId) {
        Map<Long, String> changes = new HashMap<>();

        try {
            String key = String.format(SEAT_STATUS_KEY, eventId);
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                changes.put(Long.parseLong(entry.getKey().toString()),
                           entry.getValue().toString());
            }

            log.debug("Retrieved {} recent seat changes for eventId={}", changes.size(), eventId);
        } catch (Exception e) {
            log.error("Failed to get recent changes for eventId={}", eventId, e);
        }

        return changes;
    }

    /**
     * Get count of seats in each status from the cache.
     */
    public Map<String, Long> getRecentStatusCounts(Long eventId) {
        Map<String, Long> counts = new HashMap<>();
        counts.put("HELD", 0L);
        counts.put("BOOKED", 0L);
        counts.put("AVAILABLE", 0L);

        try {
            Map<Long, String> changes = getRecentChanges(eventId);
            for (String status : changes.values()) {
                counts.merge(status, 1L, Long::sum);
            }
        } catch (Exception e) {
            log.error("Failed to get recent status counts for eventId={}", eventId, e);
        }

        return counts;
    }

    /**
     * Clear all cached status data for an event.
     */
    public void clearRecentChanges(Long eventId) {
        try {
            redisTemplate.delete(String.format(SEAT_STATUS_KEY, eventId));
            log.info("Cleared recent changes for eventId={}", eventId);
        } catch (Exception e) {
            log.error("Failed to clear recent changes for eventId={}", eventId, e);
        }
    }
}
