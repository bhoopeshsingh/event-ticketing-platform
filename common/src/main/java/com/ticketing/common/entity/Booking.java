package com.ticketing.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_booking_customer", columnList = "customer_id"),
    @Index(name = "idx_booking_event", columnList = "event_id"),
    @Index(name = "idx_booking_status", columnList = "status"),
    @Index(name = "idx_booking_date", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "booking_reference", nullable = false, unique = true)
    private String bookingReference; // External reference for customers

    @NotNull
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "booking_seats",
        joinColumns = @JoinColumn(name = "booking_id")
    )
    @Column(name = "seat_ids")
    private List<Long> seatIds;

    @NotNull
    @DecimalMin("0.00")
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @NotNull
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Size(max = 100)
    @Column(name = "payment_id")
    private String paymentId; // External payment reference

    @Size(max = 100)
    @Column(name = "hold_token")
    private String holdToken; // Reference to original hold

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public enum BookingStatus {
        CONFIRMED, CANCELLED, REFUNDED
    }

    // Helper methods
    public int getTicketCount() {
        return seatIds != null ? seatIds.size() : 0;
    }

    public boolean isConfirmed() {
        return status == BookingStatus.CONFIRMED;
    }

    public boolean isCancelled() {
        return status == BookingStatus.CANCELLED;
    }

    public void confirm(String paymentId) {
        this.status = BookingStatus.CONFIRMED;
        this.paymentId = paymentId;
        this.confirmedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }
}
