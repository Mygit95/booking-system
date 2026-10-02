package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.ReservationSeat;
import com.reservation.booking_system.entity.ReservationSeatId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, ReservationSeatId> {

}