package com.ticketing.common.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatHoldRequest {

    @NotNull(message = "Customer ID is required")
    private Long customerId;

    @NotNull(message = "Event ID is required")
    private Long eventId;

    @NotEmpty(message = "Seat IDs are required")
    @Size(min = 1, max = 10, message = "Can hold between 1 and 10 seats at a time")
    private List<Long> seatIds;

    // Optional: hold duration in minutes (default 10)
    @Min(value = 1, message = "Hold duration must be at least 1 minute")
    @Max(value = 30, message = "Hold duration cannot exceed 30 minutes")
    private Integer holdDurationMinutes = 10;

    // Optional: idempotency key to prevent duplicate holds
    private String idempotencyKey;
}
