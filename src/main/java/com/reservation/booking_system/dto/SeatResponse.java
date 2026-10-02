package com.reservation.booking_system.dto;

import java.util.UUID;

public record SeatResponse(
        UUID id,
        String seat_number,
        String status
) {
}