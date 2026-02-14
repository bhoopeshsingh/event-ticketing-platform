-- Event Ticketing Platform Database Initialization
-- This script creates the necessary tables for the event ticketing system

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create tables
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    venue VARCHAR(200) NOT NULL,
    event_date TIMESTAMP NOT NULL,
    total_capacity INTEGER NOT NULL,
    available_seats INTEGER NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    organizer_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);

-- Create indexes for events
CREATE INDEX idx_event_city_date ON events(city, event_date);
CREATE INDEX idx_event_category ON events(category);
CREATE INDEX idx_event_status ON events(status);
CREATE INDEX idx_event_organizer ON events(organizer_id);
CREATE INDEX idx_event_date ON events(event_date);

-- Create seats table
CREATE TABLE seats (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id),
    section VARCHAR(50) NOT NULL,
    row_letter VARCHAR(10) NOT NULL,
    seat_number INTEGER NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    seat_identifier VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(event_id, row_letter, seat_number)
);

-- Create indexes for seats
CREATE INDEX idx_seat_event ON seats(event_id);
CREATE INDEX idx_seat_status ON seats(status);
CREATE INDEX idx_seat_section ON seats(event_id, section);

-- Create pricing tiers table
CREATE TABLE pricing_tiers (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    max_quantity INTEGER,
    available_quantity INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create seat holds table
CREATE TABLE seat_holds (
    id BIGSERIAL PRIMARY KEY,
    hold_token VARCHAR(255) UNIQUE NOT NULL,
    customer_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL REFERENCES events(id),
    seat_ids BIGINT[] NOT NULL,
    seat_count INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for seat holds
CREATE INDEX idx_seat_hold_token ON seat_holds(hold_token);
CREATE INDEX idx_seat_hold_customer ON seat_holds(customer_id);
CREATE INDEX idx_seat_hold_event ON seat_holds(event_id);
CREATE INDEX idx_seat_hold_expires ON seat_holds(expires_at);
CREATE INDEX idx_seat_hold_status ON seat_holds(status);

-- Create bookings table
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    booking_reference VARCHAR(50) UNIQUE NOT NULL,
    customer_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL REFERENCES events(id),
    seat_ids BIGINT[] NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_id VARCHAR(255),
    hold_token VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP
);

-- Create indexes for bookings
CREATE INDEX idx_booking_reference ON bookings(booking_reference);
CREATE INDEX idx_booking_customer ON bookings(customer_id);
CREATE INDEX idx_booking_event ON bookings(event_id);
CREATE INDEX idx_booking_status ON bookings(status);
CREATE INDEX idx_booking_payment ON bookings(payment_id);
CREATE INDEX idx_booking_hold_token ON bookings(hold_token);

-- Insert sample data for testing
-- Master data (matches README):
-- - 3 events
-- - 100 seats for Event #1
-- - 3 pricing tiers total (for Event #1)
INSERT INTO events (id, title, description, category, city, venue, event_date, total_capacity, available_seats, base_price, status, organizer_id) VALUES
(1, 'Rock Concert 2026', 'Amazing rock concert with international artists', 'Music', 'New York', 'Madison Square Garden', '2026-06-15 20:00:00', 100, 100, 150.00, 'PUBLISHED', 1),
(2, 'Tech Conference 2026', 'Latest trends in technology and AI', 'Technology', 'San Francisco', 'Moscone Center', '2026-07-20 09:00:00', 500, 500, 299.00, 'PUBLISHED', 2),
(3, 'Comedy Show', 'Stand-up comedy night with top comedians', 'Comedy', 'Los Angeles', 'Hollywood Bowl', '2026-08-10 19:30:00', 750, 750, 75.00, 'PUBLISHED', 3)
ON CONFLICT (id) DO NOTHING;

-- Keep identity sequence in sync (in case ids were inserted explicitly)
SELECT setval(pg_get_serial_sequence('events', 'id'), (SELECT COALESCE(MAX(id), 1) FROM events), true);

-- Insert sample seats for the first event
INSERT INTO seats (event_id, section, row_letter, seat_number, price, status, seat_identifier)
SELECT
    1 as event_id,
    CASE
        WHEN (generate_series % 100) < 20 THEN 'VIP'
        WHEN (generate_series % 100) < 60 THEN 'Premium'
        ELSE 'Regular'
    END as section,
    CHR(65 + (generate_series / 20)) as row_letter,
    ((generate_series % 20) + 1) as seat_number,
    CASE
        WHEN (generate_series % 100) < 20 THEN 300.00
        WHEN (generate_series % 100) < 60 THEN 200.00
        ELSE 150.00
    END as price,
    'AVAILABLE' as status,
    CASE
        WHEN (generate_series % 100) < 20 THEN 'VIP'
        WHEN (generate_series % 100) < 60 THEN 'Premium'
        ELSE 'Regular'
    END || '-' || CHR(65 + (generate_series / 20)) || ((generate_series % 20) + 1) as seat_identifier
FROM generate_series(0, 99)
ON CONFLICT DO NOTHING;

-- Insert pricing tiers for events
INSERT INTO pricing_tiers (event_id, name, description, price, max_quantity, available_quantity) VALUES
(1, 'VIP', 'VIP seats with exclusive access', 300.00, 20, 20),
(1, 'Premium', 'Premium seats with great view', 200.00, 40, 40),
(1, 'Regular', 'Regular seats', 150.00, 40, 40);

-- Create function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create triggers to automatically update updated_at
CREATE TRIGGER update_events_updated_at BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_seats_updated_at BEFORE UPDATE ON seats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_seat_holds_updated_at BEFORE UPDATE ON seat_holds
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO ticketing_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO ticketing_user;
