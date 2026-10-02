CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL DEFAULT 4
        CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMPTZ,

    CONSTRAINT fk_reservation_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id),

    CONSTRAINT chk_reservation_status
        CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);


CREATE TABLE seats (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    seat_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    reservation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_seat_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id),

    CONSTRAINT fk_seat_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id),

    CONSTRAINT uq_show_seat
        UNIQUE (show_id, seat_number),

    CONSTRAINT chk_seat_status
        CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED'))
);


CREATE TABLE reservation_seats (
    reservation_id UUID NOT NULL,
    seat_id UUID NOT NULL,

    PRIMARY KEY (reservation_id, seat_id),

    CONSTRAINT fk_reservation_seats_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id),

    CONSTRAINT fk_reservation_seats_seat
        FOREIGN KEY (seat_id)
        REFERENCES seats(id)
);


CREATE TABLE user_show_limits (
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    reserved_count INTEGER NOT NULL DEFAULT 0
        CHECK (reserved_count >= 0),

    PRIMARY KEY (show_id, user_id),

    CONSTRAINT fk_user_show_limit_show
        FOREIGN KEY (show_id)
        REFERENCES shows(id)
);


CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_idempotency_reservation
        FOREIGN KEY (reservation_id)
        REFERENCES reservations(id),

    CONSTRAINT uq_idempotency_key
        UNIQUE (show_id, user_id, idempotency_key)
);