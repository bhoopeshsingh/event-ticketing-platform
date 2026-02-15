# Event Ticketing Platform

A B2C event ticketing platform built as Spring Boot microservices with a **10-minute seat hold (TTL)** flow designed for **zero double bookings**, deterministic auto-release, and auditability.

## Core feature: seat hold with 10-minute expiry

**Problems solved**
- Prevent double booking during high-traffic events
- Give users time to complete payment without locking seats indefinitely
- Automatically release seats if payment isn't completed

**How it works (high level)**
- **Per-seat Redis hold keys** prevent concurrent holds on the same seat.
- **PostgreSQL is the source of truth** and guards seat state transitions.
- **Redis TTL expiry** triggers a keyspace notification; booking service publishes a lightweight Kafka event.
- **Kafka consumer** performs DB cleanup with retries and publishes audit events.
- **Real-time seat browsing** uses a short-lived Redis “recent changes” overlay merged with DB seat data.

## Architecture

### Implemented Microservices
1. ✅ **Event Service** (Port 8081) - Event discovery, seat layouts
2. ✅ **Booking Service** (Port 8082) - Seat holds, booking confirmation
3. ✅ **Common Module** - Shared entities, DTOs, utilities
4. 🚧 **Queue Service** - Virtual waiting room (planned)
5. 🚧 **API Gateway** - Unified entry point (planned)

### Technology Stack
- **Language**: Java 21
- **Framework**: Spring Boot 3.2.x
- **Database**: PostgreSQL 15 (persistence)
- **Cache**: Redis 7 (distributed locks + TTL + caching)
- **Messaging**: Apache Kafka 3.x (event streaming)
- **API Docs**: SpringDoc OpenAPI 3
- **Build**: Maven 3.8+
- **Containerization**: Docker Compose

## Data model

### Database Tables (PostgreSQL)
- **events** - Event catalog (title, venue, date, capacity, status)
- **seats** - Seat inventory (section, row, number, price, status)
- **seat_holds** - Temporary reservations with TTL (hold_token, expires_at, status)
- **bookings** - Confirmed purchases (booking_reference, payment_id)
- **pricing_tiers** - Dynamic pricing (VIP, Premium, Regular)

### Sample Data Loaded
- 3 Events: Rock Concert 2026, Tech Conference 2026, Comedy Show
- 100 Seats for Event #1 with multiple pricing tiers
- 3 Pricing tiers across all events

## Redis key design (current implementation)

### Per-seat hold keys (locks + TTL)
- **Key**: `seat:{eventId}:{seatId}:HELD`
- **Value**: `{customerId}:{holdToken}`
- **TTL**: hold duration (default 10 minutes)
- **Acquire**: `SET key value NX EX <ttlSeconds>`

### Real-time seat status overlay
Event-service merges a short-lived Redis overlay into the DB seat list so users see near real-time changes.
- **Key (HASH)**: `{eventId}:seat_status`
- **Field**: seatId (string)
- **Value**: status (`HELD` | `BOOKED` | `AVAILABLE`)
- **TTL**: 600 seconds (refreshed on every write)

Each seat appears as a single HASH field, so status updates overwrite the previous value atomically. No seat can appear in two status groups at once.

### Redis DB alignment (important)
For the overlay to work, **both services must use the same Redis database index**.
- `booking-service`: `spring.data.redis.database` defaults to `0`
- `event-service`: configured to use the same DB (`0`)

## Event streaming (Kafka)
Lifecycle/audit events published by the booking domain (topic names may vary by environment):
- `SEAT_HOLD_CREATED`
- `SEAT_HOLD_EXPIRED` (origin: Redis TTL -> booking service -> Kafka -> DB cleanup)
- `BOOKING_CONFIRMED`
- `SEAT_HOLD_CANCELLED`

## API endpoints

### Event Service (Port 8081)
```
GET    /api/events/upcoming                    # List upcoming events
GET    /api/events?city={city}&date={date}     # Search by filters
GET    /api/events/{id}                        # Event details
GET    /api/events/{id}/seats                  # Seat layout
GET    /api/events/cities                      # Available cities
GET    /api/events/categories                  # Event categories
POST   /api/events                             # Create event (organizer)
PUT    /api/events/{id}                        # Update event
DELETE /api/events/{id}                        # Cancel event
```

### Booking Service (Port 8082)
```
POST   /api/bookings/hold                      # Hold seats (e.g. 10-min TTL)
GET    /api/bookings/hold/{holdToken}          # Check hold status
POST   /api/bookings/{holdToken}/confirm       # Confirm with payment
DELETE /api/bookings/hold/{holdToken}          # Cancel hold
GET    /api/bookings/{bookingReference}        # Get booking details
```

**Swagger UI:**
- Event Service: http://localhost:8081/swagger-ui.html
- Booking Service: http://localhost:8082/swagger-ui.html

## 10-minute seat hold flow (implemented)

```
1. User selects seats
   ↓
2. POST /api/bookings/hold
   {
     "customerId": 1,
     "eventId": 1,
     "seatIds": [1, 2, 3],
     "holdDurationMinutes": 10
   }
   ↓
3. Booking Service:
   - Acquires per-seat Redis hold keys: seat:{eventId}:{seatId}:HELD (SET NX EX)
   - DB guard update to HELD (reject if already unavailable)
   - Persists SeatHold in DB (source of truth)
   - Writes to Redis seat status HASH: {eventId}:seat_status
   - Publishes audit event (Kafka)
   ↓
4. Returns hold token to user:
   {
     "holdToken": "HOLD_xxx",
     "expiresAt": "2026-02-10T18:46:00",
     "timeRemainingSeconds": 600
   }
   ↓
5. User has 10 minutes to complete payment
   ↓
6a. SUCCESS: POST /api/bookings/{holdToken}/confirm
    → Validate: SeatHold ACTIVE + not expired (DB source of truth, no Redis check)
    → DB guard: UPDATE seats SET status='BOOKED' WHERE status='HELD'
    → Create Booking record, confirm SeatHold
    → afterCommit: update Redis seat status HASH → BOOKED
    → afterCommit: release per-seat Redis hold keys (if they still exist)
    → afterCommit: publish "BookingConfirmed" event (Kafka)
   
6b. TIMEOUT: seat:{eventId}:{seatId}:HELD expires (Redis TTL)
   → Redis keyspace notification "__keyevent@0__:expired"
   → Booking service publishes Kafka seat-state transition event
   → SeatStateConsumer updates DB seat to AVAILABLE (if still HELD)
   → Update Redis seat status HASH: {eventId}:seat_status → AVAILABLE
   → Publish audit event
```

### Key Implementation Details

**Distributed Locking:**
```java
// Per-seat hold key (also acts as the distributed lock)
String key = String.format("seat:%d:%d:HELD", eventId, seatId);
String value = customerId + ":" + holdToken;
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, value, holdDuration);
```

**Keyspace Notifications:**
```java
// Listen for expired keys
@PostConstruct
public void init() {
    redisMessageListenerContainer.addMessageListener(
        this, new PatternTopic("__keyevent@0__:expired")
    );
}
```

## Quick start

### Prerequisites
- Java 21
- Maven 3.8+
- Docker & Docker Compose

### 1. Start Infrastructure
```bash
# Start PostgreSQL, Redis, Kafka, Zookeeper
docker-compose up -d

# Wait for services to be healthy (~30 seconds)
docker-compose ps
```

### 2. Build Project
```bash
# Build all modules
mvn clean install

# Expected: BUILD SUCCESS for all 6 modules
```

### 3. Start Services
```bash
# Option 1: Use startup script
chmod +x start-services.sh
./start-services.sh

# Option 2: Manual (separate terminals)
cd event-service && mvn spring-boot:run
cd booking-service && mvn spring-boot:run
```

### 4. Verify Services
```bash
# Health checks
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# Expected: {"status":"UP"}
```

### 5. Access APIs
- **Event Service Swagger**: http://localhost:8081/swagger-ui.html
- **Booking Service Swagger**: http://localhost:8082/swagger-ui.html

## Test the seat hold feature

### 1. Hold Seats (10-minute TTL)
```bash
curl -X POST http://localhost:8082/api/bookings/hold \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 1,
    "eventId": 1,
    "seatIds": [1, 2, 3],
    "holdDurationMinutes": 10
  }'
```

**Response:**
```json
{
  "holdToken": "HOLD_1234567890",
  "eventId": 1,
  "seatIds": [1, 2, 3],
  "totalAmount": 450.00,
  "expiresAt": "2026-02-10T18:46:00",
  "timeRemainingSeconds": 600,
  "status": "ACTIVE"
}
```

### 2. Monitor Redis TTL
```bash
# Connect to Redis
docker exec -it ticketing-redis redis-cli

# Check per-seat hold keys exist
> KEYS seat:1:*:HELD

# Watch TTL countdown (seconds remaining) for a seat hold key
> TTL seat:1:1:HELD

# Inspect seat status overlay (HASH)
> HGETALL 1:seat_status

# Monitor expiry events
> PSUBSCRIBE '__keyevent@0__:expired'
```

### 3. Monitor Kafka Events
```bash
docker exec ticketing-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic booking-events \
  --from-beginning \
  --property print.timestamp=true
```

### 4. Query Database
```bash
# Connect with psql
docker exec -it ticketing-postgres psql -U ticketing_user -d ticketing

# View active holds
SELECT * FROM seat_holds WHERE status = 'ACTIVE';

# View held seats
SELECT * FROM seats WHERE status = 'HELD';
```

### 5. Confirm Booking (within 10 minutes)
```bash
curl -X POST http://localhost:8082/api/bookings/HOLD_1234567890/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "holdToken": "HOLD_1234567890",
    "paymentId": "PAY_ABC123",
    "customerId": 1,
    "customerEmail": "user@example.com"
  }'
```

## Connection details

### PostgreSQL (pgAdmin/DBeaver)
```
Host:     localhost
Port:     5432
Database: ticketing
Username: ticketing_user
Password: ticketing_pass
```

### Redis (RedisInsight)
```
Host: localhost
Port: 6379
```

Redis Commander Web UI: http://localhost:8888

### Kafka
```
Bootstrap Servers: localhost:9092
```

Kafka UI Web Interface: http://localhost:9090

CLI access:
```bash
# List topics
docker exec ticketing-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Watch messages
docker exec ticketing-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic booking-events \
  --from-beginning
```

## Stop services

```bash
# Stop Spring Boot services
./stop-services.sh

# Stop infrastructure
docker-compose down

# Clean restart (removes data)
docker-compose down -v
```

## Additional files

- `docker-compose.yml` - Infrastructure configuration
- `start-services.sh` - Start all Spring Boot services
- `stop-services.sh` - Stop all services gracefully
- `simple-architecture.mmd` - High-level architecture diagram
- `seat-hold-flow.mmd` - Seat hold + TTL expiry sequence
- `REAL_TIME_STATUS_IMPLEMENTATION.md` - Implementation notes for Redis overlay
- `BUG_FIX_REDIS_DATABASE.md` - Postmortem + fixes for Redis DB alignment and component scanning

## Project status

- ✅ Core seat hold with 10-minute TTL implemented
- ✅ Per-seat distributed locking with Redis
- ✅ Real-time seat browsing overlay (Redis ZSET sliding window)
- ✅ Event streaming with Kafka (audit trail + expiry processing)
- ✅ RESTful APIs with Swagger documentation
- ✅ Docker Compose infrastructure
- ✅ Sample data loaded

## License

MIT License


