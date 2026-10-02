package com.reservation.booking_system.dto;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        Long price_paise,
        Integer total_seats,
        Integer available_seats,
        Integer held_seats,
        Integer confirmed_seats,
        List<SeatResponse> seats
) {
}