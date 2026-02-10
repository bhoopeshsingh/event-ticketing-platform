# Event Ticketing Platform

A scalable, high-performance B2C event ticketing platform with **10-minute seat hold TTL** feature built with Spring Boot microservices architecture.

## 🎯 Core Feature: Seat Hold with 10-Minute Expiry

**The Problem:**
- Prevent double booking during high-traffic events
- Give users time to complete payment without locking seats indefinitely
- Automatically release seats if payment isn't completed

**The Solution:**
- **Distributed Locking**: Redis-based locks prevent race conditions
- **TTL-based Expiry**: Redis automatically expires holds after 10 minutes
- **Event Streaming**: Kafka tracks all state changes for audit trail
- **Automatic Cleanup**: Background job + Redis keyspace notifications

## 🏗️ Architecture

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

## 📊 Data Model

### Database Tables (PostgreSQL)
- **events** - Event catalog (title, venue, date, capacity, status)
- **seats** - Seat inventory (section, row, number, price, status)
- **seat_holds** - Temporary reservations with TTL (hold_token, expires_at, status)
- **bookings** - Confirmed purchases (booking_reference, payment_id)
- **pricing_tiers** - Dynamic pricing (VIP, Premium, Regular)

### Sample Data Loaded
- 3 Events: Rock Concert 2026, Tech Conference 2026, Comedy Show
- 1000 Seats for Event #1 with multiple pricing tiers
- 7 Pricing tiers across all events

## 🔄 Key Design Patterns

### Concurrency Control
- **Pessimistic Locking**: Database row locks for seat reservation
- **Distributed Locking**: Redis-based locks across services
- **Optimistic Locking**: Version-based updates for event metadata
- **Idempotency**: Request keys to prevent duplicate bookings

### Resilience Patterns
- **Circuit Breaker**: Fail-fast for downstream services
- **Retry with Backoff**: Transient failure recovery
- **Bulkhead**: Resource isolation between services
- **Timeout**: Prevent cascading delays
- **Fallback**: Degraded mode with cached data

### Caching Strategy
- **L1 Cache**: Application-level (Caffeine)
- **L2 Cache**: Distributed (Redis)
- **CDN**: Static seat layouts
- **Write-through**: Update cache on write
- **Cache-aside**: Read-through pattern
- **TTL-based Expiry**: Auto-refresh stale data

### Event Streaming
- **Seat Hold Created**: When user reserves seats
- **Seat Hold Expired**: Redis TTL triggers cleanup
- **Seat Hold Confirmed**: Payment successful
- **Seat Hold Cancelled**: User/system cancellation
- **Event Created/Updated**: Organizer actions

## 🚀 API Endpoints

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
POST   /api/bookings/hold                      # Hold seats (10-min TTL)
GET    /api/bookings/hold/{holdToken}          # Check hold status
POST   /api/bookings/{holdToken}/confirm       # Confirm with payment
DELETE /api/bookings/hold/{holdToken}          # Cancel hold
GET    /api/bookings/{bookingReference}        # Get booking details
```

**Swagger UI:**
- Event Service: http://localhost:8081/swagger-ui.html
- Booking Service: http://localhost:8082/swagger-ui.html

## 🔐 10-Minute Seat Hold Flow (Implemented)

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
   - Acquires distributed lock (Redis SETNX)
   - Validates seat availability (PostgreSQL)
   - Creates SeatHold record (DB + Redis with 600s TTL)
   - Updates seat status to HELD
   - Publishes "SeatHoldCreated" event (Kafka)
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
    → Create Booking record
    → Update seat status to BOOKED
    → Delete hold from Redis
    → Publish "BookingConfirmed" event
   
6b. TIMEOUT: Redis TTL expires after 10 minutes
    → Redis keyspace notification "__keyevent@0__:expired"
    → SeatHoldExpiryService listener triggered
    → Update seat status to AVAILABLE
    → Delete SeatHold record
    → Publish "SeatHoldExpired" event
```

### Key Implementation Details

**Distributed Locking:**
```java
// Prevent race conditions across service instances
String lockKey = "lock:seat:" + seatId;
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "locked", 5, TimeUnit.SECONDS);
```

**Redis TTL:**
```java
// Auto-expire hold after 10 minutes
String holdKey = "seat_hold:" + holdToken;
redisTemplate.opsForValue()
    .set(holdKey, seatHoldData, 10, TimeUnit.MINUTES);
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

## 🏃 Quick Start

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

## 🧪 Test the Seat Hold Feature

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

# Check hold exists
> KEYS seat_hold:*

# Watch TTL countdown (seconds remaining)
> TTL seat_hold:HOLD_1234567890

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

## 🗄️ Database Connection

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

**Redis Commander Web UI:** http://localhost:8888

### Kafka
```
Bootstrap Servers: localhost:9092
```

**Kafka UI Web Interface:** http://localhost:9090

**CLI Access:**
```bash
# List topics
docker exec ticketing-kafka kafka-topics --bootstrap-server localhost:9092 --list

# Watch messages
docker exec ticketing-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic booking-events \
  --from-beginning
```

## 🛑 Stop Services

```bash
# Stop Spring Boot services
./stop-services.sh

# Stop infrastructure
docker-compose down

# Clean restart (removes data)
docker-compose down -v
```

## 📚 Additional Files

- **docker-compose.yml** - Infrastructure configuration
- **infrastructure/init-db.sql** - Database schema
- **start-services.sh** - Start all Spring Boot services
- **stop-services.sh** - Stop all services gracefully
- **verify-services.sh** - Service health checker

## 🎯 Project Status

- ✅ Core seat hold with 10-minute TTL implemented
- ✅ Distributed locking with Redis
- ✅ Event streaming with Kafka
- ✅ RESTful APIs with Swagger documentation
- ✅ Docker Compose infrastructure
- ✅ Sample data loaded (3 events, 1000 seats)
- 🚧 Queue Service for flash sales (planned)
- 🚧 API Gateway (planned)

## 📝 License

MIT License


