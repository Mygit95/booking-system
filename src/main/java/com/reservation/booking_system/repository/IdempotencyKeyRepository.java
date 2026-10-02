package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByShowIdAndUserIdAndIdempotencyKey(
            UUID showId,
            String userId,
            String idempotencyKey
    );
}