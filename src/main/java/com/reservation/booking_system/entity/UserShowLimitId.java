package com.reservation.booking_system.entity;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserShowLimitId implements Serializable {

    private UUID showId;
    private String userId;
}