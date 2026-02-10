package com.ticketing.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "pricing_tiers", indexes = {
    @Index(name = "idx_pricing_event", columnList = "event_id"),
    @Index(name = "idx_pricing_tier", columnList = "tier_name")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @NotBlank
    @Size(max = 50)
    @Column(name = "tier_name", nullable = false)
    private String tierName; // VIP, Premium, Regular, Economy

    @NotNull
    @DecimalMin("0.00")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Size(max = 500)
    private String description;

    @NotNull
    @Min(0)
    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @NotNull
    @Min(0)
    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean hasAvailableSeats() {
        return availableSeats > 0;
    }

    public void reserveSeats(int count) {
        if (availableSeats >= count) {
            this.availableSeats -= count;
        } else {
            throw new IllegalStateException("Not enough available seats in tier: " + tierName);
        }
    }

    public void releaseSeats(int count) {
        this.availableSeats = Math.min(this.availableSeats + count, this.totalSeats);
    }

    public double getOccupancyRate() {
        return totalSeats > 0 ? (double) (totalSeats - availableSeats) / totalSeats : 0.0;
    }
}
