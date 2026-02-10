package com.ticketing.common.entity;

import com.ticketing.common.enums.EventStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "events", indexes = {
    @Index(name = "idx_event_city_date", columnList = "city, event_date"),
    @Index(name = "idx_event_category", columnList = "category"),
    @Index(name = "idx_event_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    private String title;

    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String description;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    private String category;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false)
    private String city;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    private String venue;

    @NotNull
    @Future
    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @NotNull
    @Positive
    @Column(name = "total_capacity", nullable = false)
    private Integer totalCapacity;

    @NotNull
    @Positive
    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal basePrice;

    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    @NotNull
    @Column(name = "organizer_id", nullable = false)
    private Long organizerId;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Seat> seats;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PricingTier> pricingTiers;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version; // For optimistic locking

    // Helper methods
    public boolean hasAvailableSeats() {
        return availableSeats > 0;
    }

    public void decrementAvailableSeats(int count) {
        if (availableSeats >= count) {
            this.availableSeats -= count;
        } else {
            throw new IllegalStateException("Not enough available seats");
        }
    }

    public void incrementAvailableSeats(int count) {
        this.availableSeats += count;
    }
}
