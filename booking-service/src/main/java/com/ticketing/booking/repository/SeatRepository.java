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
           "AND s.status = com.ticketing.common.entity.Seat.SeatStatus.AVAILABLE " +
           "ORDER BY s.section, s.rowLetter, s.seatNumber")
    List<Seat> findAvailableSeatsByEvent(@Param("eventId") Long eventId);

    /**
     * Find seats by IDs with pessimistic lock for reservation
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
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
           "AND s.status = com.ticketing.common.entity.Seat.SeatStatus.AVAILABLE")
    boolean areAllSeatsAvailable(@Param("seatIds") List<Long> seatIds, @Param("expectedCount") long expectedCount);

    /**
     * Update seat status to HELD
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = com.ticketing.common.entity.Seat.SeatStatus.HELD " +
           "WHERE s.id IN :seatIds AND s.status = com.ticketing.common.entity.Seat.SeatStatus.AVAILABLE")
    int holdSeats(@Param("seatIds") List<Long> seatIds);

    /**
     * Update seat status to BOOKED
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = com.ticketing.common.entity.Seat.SeatStatus.BOOKED " +
           "WHERE s.id IN :seatIds AND s.status = com.ticketing.common.entity.Seat.SeatStatus.HELD")
    int bookSeats(@Param("seatIds") List<Long> seatIds);

    /**
     * Release held seats back to AVAILABLE
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = com.ticketing.common.entity.Seat.SeatStatus.AVAILABLE " +
           "WHERE s.id IN :seatIds AND s.status = com.ticketing.common.entity.Seat.SeatStatus.HELD")
    int releaseSeats(@Param("seatIds") List<Long> seatIds);

    /**
     * Count available seats for an event
     */
    @Query("SELECT COUNT(s) FROM Seat s WHERE s.event.id = :eventId " +
           "AND s.status = com.ticketing.common.entity.Seat.SeatStatus.AVAILABLE")
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
