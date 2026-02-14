package com.ticketing.booking.repository;

import com.ticketing.common.entity.SeatHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * Find seat hold by hold token with pessimistic lock
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sh FROM SeatHold sh WHERE sh.holdToken = :holdToken")
    Optional<SeatHold> findByHoldTokenWithLock(@Param("holdToken") String holdToken);

    /**
     * Find seat hold by hold token
     */
    Optional<SeatHold> findByHoldToken(String holdToken);

    /**
     * Find active holds for a customer
     */
    @Query("SELECT sh FROM SeatHold sh WHERE sh.customerId = :customerId " +
           "AND sh.status = 'ACTIVE' " +
           "AND sh.expiresAt > :now")
    List<SeatHold> findActiveHoldsByCustomer(@Param("customerId") Long customerId,
                                           @Param("now") LocalDateTime now);

    /**
     * Find holds for specific seats that are still active
     * Uses native query because PostgreSQL array overlap requires native syntax
     */
    @Query(value = "SELECT * FROM seat_holds sh WHERE sh.event_id = :eventId " +
           "AND sh.status = 'ACTIVE' " +
           "AND sh.expires_at > :now " +
           "AND sh.seat_ids && CAST(:seatIds AS bigint[])", 
           nativeQuery = true)
    List<SeatHold> findActiveHoldsForSeats(@Param("eventId") Long eventId,
                                          @Param("seatIds") Long[] seatIds,
                                          @Param("now") LocalDateTime now);

    /**
     * Find expired holds for cleanup
     */
    @Query("SELECT sh FROM SeatHold sh WHERE sh.status = 'ACTIVE' " +
           "AND sh.expiresAt <= :now")
    List<SeatHold> findExpiredHolds(@Param("now") LocalDateTime now);

    /**
     * Bulk update expired holds
     */
    @Modifying
    @Query("UPDATE SeatHold sh SET sh.status = 'EXPIRED' " +
           "WHERE sh.status = 'ACTIVE' " +
           "AND sh.expiresAt <= :now")
    int markExpiredHolds(@Param("now") LocalDateTime now);

    /**
     * Count active holds for an event
     */
    @Query("SELECT COUNT(sh) FROM SeatHold sh WHERE sh.event.id = :eventId " +
           "AND sh.status = 'ACTIVE' " +
           "AND sh.expiresAt > :now")
    long countActiveHoldsForEvent(@Param("eventId") Long eventId, @Param("now") LocalDateTime now);

    /**
     * Find active holds that contain a specific seat and have expired.
     * Used by SeatStateConsumer when processing TTL expiry events.
     */
    @Query(value = "SELECT * FROM seat_holds sh WHERE sh.event_id = :eventId " +
           "AND sh.status = 'ACTIVE' " +
           "AND sh.expires_at <= :now " +
           "AND :seatId = ANY(sh.seat_ids)",
           nativeQuery = true)
    List<SeatHold> findExpiredHoldsForSeat(@Param("eventId") Long eventId,
                                           @Param("seatId") Long seatId,
                                           @Param("now") LocalDateTime now);
}
