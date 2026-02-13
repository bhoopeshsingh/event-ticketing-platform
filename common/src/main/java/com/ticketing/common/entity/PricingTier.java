package com.ticketing.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "pricing_tiers", indexes = {
    @Index(name = "idx_pricing_event", columnList = "event_id")
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
    @Column(name = "name", nullable = false)
    private String tierName; // VIP, Premium, Regular, Economy

    @NotNull
    @DecimalMin("0.00")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Size(max = 500)
    private String description;

    @Column(name = "max_quantity")
    private Integer maxQuantity;

    @Column(name = "available_quantity")
    private Integer availableQuantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Helper methods
    public boolean hasAvailableSeats() {
        return availableQuantity != null && availableQuantity > 0;
    }

    public void reserveSeats(int count) {
        if (availableQuantity != null && availableQuantity >= count) {
            this.availableQuantity -= count;
        } else {
            throw new IllegalStateException("Not enough available seats in tier: " + tierName);
        }
    }

    public void releaseSeats(int count) {
        if (availableQuantity != null && maxQuantity != null) {
            this.availableQuantity = Math.min(this.availableQuantity + count, this.maxQuantity);
        }
    }

    public double getOccupancyRate() {
        if (maxQuantity != null && maxQuantity > 0 && availableQuantity != null) {
            return (double) (maxQuantity - availableQuantity) / maxQuantity;
        }
        return 0.0;
    }
}
