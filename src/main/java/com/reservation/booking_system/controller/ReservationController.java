package com.reservation.booking_system.controller;

import com.reservation.booking_system.dto.ReservationResponse;
import com.reservation.booking_system.dto.ReserveRequest;
import com.reservation.booking_system.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody ReserveRequest request) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(reservationService.reserve(
                        showId,
                        userId,
                        request
                ));
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable UUID reservationId,
            @RequestHeader("X-User-Id") String userId) {

        return ResponseEntity.ok(
                reservationService.cancel(
                        reservationId,
                        userId
                )
        );
    }
}