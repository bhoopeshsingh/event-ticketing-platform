package com.ticketing.booking.repository;

import com.ticketing.common.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Find booking by reference number
     */
    Optional<Booking> findByBookingReference(String bookingReference);

    /**
     * Find bookings by customer with pagination
     */
    Page<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    /**
     * Find confirmed bookings by customer
     */
    @Query("SELECT b FROM Booking b WHERE b.customerId = :customerId " +
           "AND b.status = 'CONFIRMED' " +
           "ORDER BY b.createdAt DESC")
    List<Booking> findConfirmedBookingsByCustomer(@Param("customerId") Long customerId);

    /**
     * Find bookings for a specific event
     */
    Page<Booking> findByEventIdOrderByCreatedAtDesc(Long eventId, Pageable pageable);

    /**
     * Find booking by hold token (for confirmation)
     */
    Optional<Booking> findByHoldToken(String holdToken);

    /**
     * Count confirmed bookings for an event
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.event.id = :eventId " +
           "AND b.status = 'CONFIRMED'")
    long countConfirmedBookingsForEvent(@Param("eventId") Long eventId);

    /**
     * Find bookings by payment ID
     */
    Optional<Booking> findByPaymentId(String paymentId);

    /**
     * Find recent bookings for monitoring
     */
    @Query("SELECT b FROM Booking b WHERE b.createdAt >= :since " +
           "ORDER BY b.createdAt DESC")
    List<Booking> findRecentBookings(@Param("since") LocalDateTime since);

    /**
     * Check if seats are already booked for an event
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END " +
           "FROM Booking b WHERE b.event.id = :eventId " +
           "AND b.status = 'CONFIRMED' " +
           "AND EXISTS (SELECT 1 FROM b.seatIds si WHERE si IN :seatIds)")
    boolean areSeatsAlreadyBooked(@Param("eventId") Long eventId, @Param("seatIds") List<Long> seatIds);
}
