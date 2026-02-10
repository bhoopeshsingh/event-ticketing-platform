package com.ticketing.event.repository;

import com.ticketing.common.entity.Event;
import com.ticketing.common.enums.EventStatus;
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
public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Find events by city with pagination and filtering
     */
    @Query("SELECT e FROM Event e WHERE " +
           "(:city IS NULL OR LOWER(e.city) = LOWER(:city)) AND " +
           "(:category IS NULL OR LOWER(e.category) = LOWER(:category)) AND " +
           "(:fromDate IS NULL OR e.eventDate >= :fromDate) AND " +
           "(:toDate IS NULL OR e.eventDate <= :toDate) AND " +
           "e.status = :status " +
           "ORDER BY e.eventDate ASC")
    Page<Event> findEventsWithFilters(@Param("city") String city,
                                     @Param("category") String category,
                                     @Param("fromDate") LocalDateTime fromDate,
                                     @Param("toDate") LocalDateTime toDate,
                                     @Param("status") EventStatus status,
                                     Pageable pageable);

    /**
     * Find events by city (most common search)
     */
    @Query("SELECT e FROM Event e WHERE LOWER(e.city) = LOWER(:city) " +
           "AND e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "ORDER BY e.eventDate ASC")
    Page<Event> findByCity(@Param("city") String city,
                          @Param("now") LocalDateTime now,
                          Pageable pageable);

    /**
     * Find events by category
     */
    @Query("SELECT e FROM Event e WHERE LOWER(e.category) = LOWER(:category) " +
           "AND e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "ORDER BY e.eventDate ASC")
    Page<Event> findByCategory(@Param("category") String category,
                              @Param("now") LocalDateTime now,
                              Pageable pageable);

    /**
     * Find events by date range
     */
    @Query("SELECT e FROM Event e WHERE " +
           "e.eventDate BETWEEN :startDate AND :endDate " +
           "AND e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "ORDER BY e.eventDate ASC")
    Page<Event> findByDateRange(@Param("startDate") LocalDateTime startDate,
                               @Param("endDate") LocalDateTime endDate,
                               Pageable pageable);

    /**
     * Find upcoming events (popular homepage query)
     */
    @Query("SELECT e FROM Event e WHERE " +
           "e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "AND e.availableSeats > 0 " +
           "ORDER BY e.eventDate ASC")
    Page<Event> findUpcomingEvents(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Find events by organizer
     */
    Page<Event> findByOrganizerIdOrderByEventDateDesc(Long organizerId, Pageable pageable);

    /**
     * Search events by title (for search autocomplete)
     */
    @Query("SELECT e FROM Event e WHERE " +
           "LOWER(e.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "AND e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "ORDER BY e.eventDate ASC")
    List<Event> searchByTitle(@Param("query") String query, @Param("now") LocalDateTime now);

    /**
     * Get distinct cities with active events
     */
    @Query("SELECT DISTINCT e.city FROM Event e WHERE " +
           "e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "ORDER BY e.city")
    List<String> findActiveCities(@Param("now") LocalDateTime now);

    /**
     * Get distinct categories with active events
     */
    @Query("SELECT DISTINCT e.category FROM Event e WHERE " +
           "e.status = com.ticketing.common.enums.EventStatus.PUBLISHED " +
           "AND e.eventDate > :now " +
           "ORDER BY e.category")
    List<String> findActiveCategories(@Param("now") LocalDateTime now);

    /**
     * Find event with seat information for booking
     */
    @Query("SELECT e FROM Event e LEFT JOIN FETCH e.seats " +
           "WHERE e.id = :eventId")
    Optional<Event> findByIdWithSeats(@Param("eventId") Long eventId);

    /**
     * Count events by status for admin dashboard
     */
    @Query("SELECT e.status, COUNT(e) FROM Event e GROUP BY e.status")
    List<Object[]> countByStatus();
}
