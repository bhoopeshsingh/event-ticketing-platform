package com.ticketing.common.dto;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingTierDto {

    private Long id;

    @NotNull(message = "Event ID is required")
    private Long eventId;

    @NotBlank(message = "Tier name is required")
    @Size(max = 50)
    private String tierName;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be non-negative")
    private BigDecimal price;

    @Size(max = 500)
    private String description;

    @NotNull(message = "Available seats is required")
    @Min(0)
    private Integer availableSeats;

    @NotNull(message = "Total seats is required")
    @Min(0)
    private Integer totalSeats;

    // Helper methods
    public boolean hasAvailableSeats() {
        return availableSeats > 0;
    }

    public double getOccupancyRate() {
        return totalSeats > 0 ? (double) (totalSeats - availableSeats) / totalSeats : 0.0;
    }

    public int getBookedSeats() {
        return totalSeats - availableSeats;
    }
}
