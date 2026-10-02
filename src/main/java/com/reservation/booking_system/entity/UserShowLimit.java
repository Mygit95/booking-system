package com.reservation.booking_system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "user_show_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(UserShowLimitId.class)
public class UserShowLimit {

    @Id
    @Column(name = "show_id")
    private UUID showId;

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "reserved_count", nullable = false)
    @Builder.Default
    private Integer reservedCount = 0;
}