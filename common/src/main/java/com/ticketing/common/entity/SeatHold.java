package com.ticketing.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "seat_holds", indexes = {
    @Index(name = "idx_hold_customer", columnList = "customer_id"),
    @Index(name = "idx_hold_expiry", columnList = "expires_at"),
    @Index(name = "idx_hold_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "hold_token", nullable = false, unique = true)
    private String holdToken;

    @NotNull
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Type(ListArrayType.class)
    @Column(name = "seat_ids", nullable = false, columnDefinition = "bigint[]")
    private List<Long> seatIds; // List of held seat IDs

    @NotNull
    @Column(name = "seat_count", nullable = false)
    private Integer seatCount;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private HoldStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum HoldStatus {
        ACTIVE, EXPIRED, CONFIRMED, CANCELLED
    }

    // Helper methods
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return status == HoldStatus.ACTIVE && !isExpired();
    }

    public int getSeatCount() {
        return seatIds != null ? seatIds.size() : 0;
    }

    public void expire() {
        this.status = HoldStatus.EXPIRED;
    }

    public void confirm() {
        this.status = HoldStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = HoldStatus.CANCELLED;
    }
}
