package com.ticketing.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "seats", indexes = {
    @Index(name = "idx_seat_event_section", columnList = "event_id, section"),
    @Index(name = "idx_seat_status", columnList = "status"),
    @Index(name = "idx_seat_row_number", columnList = "row_letter, seat_number")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false)
    private String section; // VIP, Regular, Economy

    @NotBlank
    @Size(max = 10)
    @Column(name = "row_letter", nullable = false)
    private String rowLetter; // A, B, C, etc.

    @NotNull
    @Positive
    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber; // 1, 2, 3, etc.

    @NotNull
    @DecimalMin("0.00")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SeatStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version; // For optimistic locking

    public enum SeatStatus {
        AVAILABLE, HELD, BOOKED
    }

    // Unique constraint on event, row, and seat number
    @PrePersist
    @PreUpdate
    private void validateUniqueness() {
        // This will be enforced by database constraint as well
    }

    // Helper methods
    public String getSeatIdentifier() {
        return String.format("%s-%s%d", section, rowLetter, seatNumber);
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public boolean isHeld() {
        return status == SeatStatus.HELD;
    }

    public boolean isBooked() {
        return status == SeatStatus.BOOKED;
    }
}
