package com.ticketing.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = "booking.hold.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class SeatHoldCleanupJob {

    private final SeatHoldExpiryService seatHoldExpiryService;

    /**
     * Scheduled cleanup job that runs every 2 minutes as backup to Redis TTL notifications
     * This ensures that expired holds are cleaned up even if Redis notifications are missed
     */
   // @Scheduled(fixedRateString = "${booking.hold.cleanup.interval.minutes:2}000")
    public void cleanupExpiredHolds() {
        log.debug("Starting scheduled cleanup of expired seat holds");

        try {
            seatHoldExpiryService.cleanupExpiredHolds();
        } catch (Exception e) {
            log.error("Error during scheduled cleanup of expired holds", e);
        }
    }

    /**
     * Bulk cleanup job that runs every 5 minutes to mark expired holds in batch
     */
   // @Scheduled(fixedRateString = "300000") // 5 minutes
    public void bulkCleanupExpiredHolds() {
        log.debug("Starting bulk cleanup of expired seat holds");

        try {
            int cleanedCount = seatHoldExpiryService.bulkMarkExpiredHolds();
            if (cleanedCount > 0) {
                log.info("Bulk cleanup marked {} holds as expired", cleanedCount);
            }
        } catch (Exception e) {
            log.error("Error during bulk cleanup of expired holds", e);
        }
    }
}
