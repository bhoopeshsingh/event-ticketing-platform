-- Master data reseed (safe to run manually via psql)
-- Recreates the dataset referenced in README.md:
-- - 3 events
-- - 100 seats for Event #1
-- - 3 pricing tiers total (for Event #1)

BEGIN;

-- Clear transactional data first, then master data.
TRUNCATE TABLE bookings RESTART IDENTITY CASCADE;
TRUNCATE TABLE seat_holds RESTART IDENTITY CASCADE;
TRUNCATE TABLE seats RESTART IDENTITY CASCADE;
TRUNCATE TABLE pricing_tiers RESTART IDENTITY CASCADE;
TRUNCATE TABLE events RESTART IDENTITY CASCADE;

INSERT INTO events (id, title, description, category, city, venue, event_date, total_capacity, available_seats, base_price, status, organizer_id) VALUES
(1, 'Rock Concert 2026', 'Amazing rock concert with international artists', 'Music', 'New York', 'Madison Square Garden', '2026-06-15 20:00:00', 100, 100, 150.00, 'PUBLISHED', 1),
(2, 'Tech Conference 2026', 'Latest trends in technology and AI', 'Technology', 'San Francisco', 'Moscone Center', '2026-07-20 09:00:00', 500, 500, 299.00, 'PUBLISHED', 2),
(3, 'Comedy Show', 'Stand-up comedy night with top comedians', 'Comedy', 'Los Angeles', 'Hollywood Bowl', '2026-08-10 19:30:00', 750, 750, 75.00, 'PUBLISHED', 3);

SELECT setval(pg_get_serial_sequence('events', 'id'), 3, true);

INSERT INTO seats (event_id, section, row_letter, seat_number, price, status, seat_identifier)
SELECT
    1 as event_id,
    CASE
        WHEN (generate_series % 100) < 20 THEN 'VIP'
        WHEN (generate_series % 100) < 60 THEN 'Premium'
        ELSE 'Regular'
    END as section,
    CHR(65 + (generate_series / 20)) as row_letter,        -- A..E
    ((generate_series % 20) + 1) as seat_number,           -- 1..20
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
FROM generate_series(0, 99);

INSERT INTO pricing_tiers (event_id, name, description, price, max_quantity, available_quantity) VALUES
(1, 'VIP', 'VIP seats with exclusive access', 300.00, 20, 20),
(1, 'Premium', 'Premium seats with great view', 200.00, 40, 40),
(1, 'Regular', 'Regular seats', 150.00, 40, 40);

COMMIT;

