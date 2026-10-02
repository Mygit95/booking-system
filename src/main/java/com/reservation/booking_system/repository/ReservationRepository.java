package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReservationRepository
        extends JpaRepository<Reservation, UUID> {
}