package com.ticketing.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingDto {

    private Long id;

    @NotBlank(message = "Booking reference is required")
    private String bookingReference;

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    @NotNull(message = "Event ID is required")
    private Long eventId;

    private EventDto event; // For detailed view

    @NotEmpty(message = "Seat IDs are required")
    private List<Long> seatIds;

    private List<SeatDto> seats; // For detailed view

    @NotNull(message = "Total amount is required")
    @DecimalMin(value = "0.00", message = "Total amount must be non-negative")
    private BigDecimal totalAmount;

    private String status; // CONFIRMED, CANCELLED, REFUNDED

    private String paymentId;

    private String holdToken;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime confirmedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime cancelledAt;

    // Helper methods
    public int getTicketCount() {
        return seatIds != null ? seatIds.size() : 0;
    }

    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }

    public boolean isRefunded() {
        return "REFUNDED".equals(status);
    }
}
