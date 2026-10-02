package com.reservation.booking_system.service;

import com.reservation.booking_system.dto.CreateShowRequest;
import com.reservation.booking_system.dto.ShowResponse;

import java.util.UUID;

public interface ShowService {

    ShowResponse createShow(CreateShowRequest request);

    ShowResponse getShow(UUID showId);
}
