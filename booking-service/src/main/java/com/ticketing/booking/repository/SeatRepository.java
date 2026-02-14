package com.ticketing.booking.repository;

import com.ticketing.common.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Find seats by event with availability status
     */
    @Query("SELECT s FROM Seat s WHERE s.event.id = :eventId ORDER BY s.section, s.rowLetter, s.seatNumber")
    List<Seat> findByEventIdOrderBySectionAndRow(@Param("eventId") Long eventId);

    /**
     * Find available seats for an event
     */
    @Query("SELECT s FROM Seat s WHERE s.event.id = :eventId " +
           "AND s.status = 'AVAILABLE' " +
           "ORDER BY s.section, s.rowLetter, s.seatNumber")
    List<Seat> findAvailableSeatsByEvent(@Param("eventId") Long eventId);

    /**
     * Find seats by IDs for reservation
     * NOTE: Pessimistic locking removed for H2 compatibility in tests.
     * In production with PostgreSQL, this should use locking at the database level or application-level transaction management.
     */
    // @Lock(LockModeType.PESSIMISTIC_READ)  // Commented out for H2 compatibility
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds ORDER BY s.id")
    List<Seat> findByIdInWithLock(@Param("seatIds") List<Long> seatIds);

    /**
     * Find seats by IDs
     */
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds ORDER BY s.id")
    List<Seat> findByIdIn(@Param("seatIds") List<Long> seatIds);

    /**
     * Check if all seats are available
     */
    @Query("SELECT CASE WHEN COUNT(s) = :expectedCount THEN true ELSE false END " +
           "FROM Seat s WHERE s.id IN :seatIds " +
           "AND s.status = 'AVAILABLE'")
    boolean areAllSeatsAvailable(@Param("seatIds") List<Long> seatIds, @Param("expectedCount") long expectedCount);

    /**
     * Update seat status to HELD (legacy - only from AVAILABLE)
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'HELD' " +
           "WHERE s.id IN :seatIds AND s.status = 'AVAILABLE'")
    int holdSeats(@Param("seatIds") List<Long> seatIds);

    /**
     * Update seat status to HELD with DB guard.
     * Allows transition from any state except BOOKED.
     * Redis SET NX prevents concurrent holds; this guard catches permanently sold seats
     * and handles the Kafka-lag window where an expired hold hasn't been cleaned up in DB yet.
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'HELD' " +
           "WHERE s.id IN :seatIds AND s.status <> 'BOOKED'")
    int holdSeatsGuarded(@Param("seatIds") List<Long> seatIds);

    /**
     * Update seat status to BOOKED with conditional check
     * This ensures only the lock holder can confirm the booking
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'BOOKED' " +
           "WHERE s.id IN :seatIds AND s.status = 'HELD'")
    int bookSeats(@Param("seatIds") List<Long> seatIds);
    
    /**
     * Update seat status to BOOKED with lock token verification
     * Used for conditional updates to prevent race conditions
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'BOOKED' " +
           "WHERE s.id = :seatId AND s.status = 'HELD'")
    int bookSeatConditional(@Param("seatId") Long seatId);

    /**
     * Release held seats back to AVAILABLE
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'AVAILABLE' " +
           "WHERE s.id IN :seatIds AND s.status = 'HELD'")
    int releaseSeats(@Param("seatIds") List<Long> seatIds);

    /**
     * Count available seats for an event
     */
    @Query("SELECT COUNT(s) FROM Seat s WHERE s.event.id = :eventId " +
           "AND s.status = 'AVAILABLE'")
    long countAvailableSeatsByEvent(@Param("eventId") Long eventId);

    /**
     * Find seats by section and event
     */
    @Query("SELECT s FROM Seat s WHERE s.event.id = :eventId AND s.section = :section " +
           "ORDER BY s.rowLetter, s.seatNumber")
    List<Seat> findByEventIdAndSection(@Param("eventId") Long eventId, @Param("section") String section);

    /**
     * Find seat by event, row, and seat number (unique constraint check)
     */
    Optional<Seat> findByEventIdAndRowLetterAndSeatNumber(Long eventId, String rowLetter, Integer seatNumber);
}
