package com.reservation.booking_system.repository;

import com.reservation.booking_system.entity.UserShowLimit;
import com.reservation.booking_system.entity.UserShowLimitId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface UserShowLimitRepository
        extends JpaRepository<UserShowLimit, UserShowLimitId> {


    @Modifying
    @Query(value = """
    INSERT INTO user_show_limits (
        show_id,
        user_id,
        reserved_count
    )
    VALUES (
        :showId,
        :userId,
        0
    )
    ON CONFLICT (show_id, user_id)
    DO NOTHING
    """, nativeQuery = true)
    int createIfAbsent(
            @Param("showId") UUID showId,
            @Param("userId") String userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT u
        FROM UserShowLimit u
        WHERE u.showId = :showId
          AND u.userId = :userId
    """)
    UserShowLimit findForUpdate(
            @Param("showId") UUID showId,
            @Param("userId") String userId
    );
}