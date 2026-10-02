package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}