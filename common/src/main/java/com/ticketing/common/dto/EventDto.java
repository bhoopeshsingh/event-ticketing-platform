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
public class EventDto {

    private Long id;

    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must not exceed 200 characters")
    private String title;

    @NotBlank(message = "Description is required")
    @Size(max = 2000, message = "Description must not exceed 2000 characters")
    private String description;

    @NotBlank(message = "Category is required")
    @Size(max = 100)
    private String category;

    @NotBlank(message = "City is required")
    @Size(max = 100)
    private String city;

    @NotBlank(message = "Venue is required")
    @Size(max = 200)
    private String venue;

    @NotNull(message = "Event date is required")
    @Future(message = "Event date must be in the future")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime eventDate;

    @NotNull(message = "Total capacity is required")
    @Positive(message = "Total capacity must be positive")
    private Integer totalCapacity;

    private Integer availableSeats;

    @NotNull(message = "Base price is required")
    @DecimalMin(value = "0.00", message = "Base price must be non-negative")
    private BigDecimal basePrice;

    private String status;

    @NotNull(message = "Organizer ID is required")
    private Long organizerId;

    private List<PricingTierDto> pricingTiers;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    // Helper methods for frontend
    public boolean isAvailable() {
        return availableSeats != null && availableSeats > 0;
    }

    public boolean isUpcoming() {
        return eventDate != null && eventDate.isAfter(LocalDateTime.now());
    }
}
