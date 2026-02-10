#!/bin/bash

# Event Ticketing Platform - Local Startup Script
# This script starts all Spring Boot services locally

set -e

echo "🎪 Event Ticketing Platform - Local Startup"
echo "==========================================="
echo

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to check if a service is running on a port
check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1 ; then
        return 0
    else
        return 1
    fi
}

# Function to check if a service is ready
wait_for_service() {
    local name=$1
    local port=$2
    local max_attempts=60
    local attempt=1

    echo -e "${YELLOW}⏳ Waiting for $name to be ready on port $port...${NC}"

    while [ $attempt -le $max_attempts ]; do
        if check_port $port; then
            echo -e "${GREEN}✅ $name is ready!${NC}"
            return 0
        fi

        if [ $((attempt % 10)) -eq 0 ]; then
            echo "   Attempt $attempt/$max_attempts: $name not ready yet..."
        fi
        sleep 2
        attempt=$((attempt + 1))
    done

    echo -e "${RED}❌ $name failed to start after $max_attempts attempts${NC}"
    return 1
}

# Check prerequisites
echo "📋 Checking prerequisites..."
echo

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}❌ Java not found. Please install Java 21.${NC}"
    exit 1
fi
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo -e "${GREEN}✅ Java $java_version installed${NC}"

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ Maven not found. Please install Maven 3.8+.${NC}"
    exit 1
fi
mvn_version=$(mvn -version | head -n 1 | awk '{print $3}')
echo -e "${GREEN}✅ Maven $mvn_version installed${NC}"

echo

# Check if infrastructure services are running
echo "🔍 Checking infrastructure services..."
echo

# PostgreSQL
if check_port 5432; then
    echo -e "${GREEN}✅ PostgreSQL is running on port 5432${NC}"
else
    echo -e "${RED}❌ PostgreSQL is not running on port 5432${NC}"
    echo "   Start it with: brew services start postgresql@15"
    echo "   Or: pg_ctl -D /usr/local/var/postgres start"
    exit 1
fi

# Redis
if check_port 6379; then
    echo -e "${GREEN}✅ Redis is running on port 6379${NC}"
else
    echo -e "${RED}❌ Redis is not running on port 6379${NC}"
    echo "   Start it with: brew services start redis"
    echo "   Or: redis-server --notify-keyspace-events Ex"
    exit 1
fi

# Kafka
if check_port 9092; then
    echo -e "${GREEN}✅ Kafka is running on port 9092${NC}"
else
    echo -e "${YELLOW}⚠️  Kafka is not running on port 9092 (optional for testing)${NC}"
    echo "   Start it with: brew services start kafka"
fi

echo

# Check if services are already running
if check_port 8081; then
    echo -e "${YELLOW}⚠️  Event Service port 8081 is already in use${NC}"
    read -p "Kill existing process? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        lsof -ti:8081 | xargs kill -9 2>/dev/null || true
        sleep 2
    else
        echo "Exiting..."
        exit 1
    fi
fi

if check_port 8082; then
    echo -e "${YELLOW}⚠️  Booking Service port 8082 is already in use${NC}"
    read -p "Kill existing process? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        lsof -ti:8082 | xargs kill -9 2>/dev/null || true
        sleep 2
    else
        echo "Exiting..."
        exit 1
    fi
fi

# Build the project
echo "🔨 Building the project..."
echo

mvn clean install -DskipTests

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Build failed. Please fix compilation errors.${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Build successful!${NC}"
echo

# Create logs directory
mkdir -p logs

# Start Event Service
echo "🚀 Starting Event Service..."
cd event-service
nohup mvn spring-boot:run > ../logs/event-service.log 2>&1 &
EVENT_SERVICE_PID=$!
echo $EVENT_SERVICE_PID > ../logs/event-service.pid
cd ..
echo -e "${YELLOW}   Event Service starting with PID $EVENT_SERVICE_PID${NC}"

# Wait for Event Service to be ready
wait_for_service "Event Service" 8081

# Start Booking Service
echo
echo "🚀 Starting Booking Service..."
cd booking-service
nohup mvn spring-boot:run > ../logs/booking-service.log 2>&1 &
BOOKING_SERVICE_PID=$!
echo $BOOKING_SERVICE_PID > ../logs/booking-service.pid
cd ..
echo -e "${YELLOW}   Booking Service starting with PID $BOOKING_SERVICE_PID${NC}"

# Wait for Booking Service to be ready
wait_for_service "Booking Service" 8082

echo
echo "═══════════════════════════════════════════════════════"
echo -e "${GREEN}✨ All services are up and running!${NC}"
echo "═══════════════════════════════════════════════════════"
echo
echo "📊 Service URLs:"
echo "   Event Service:   http://localhost:8081"
echo "   Booking Service: http://localhost:8082"
echo
echo "📚 API Documentation:"
echo "   Event Service:   http://localhost:8081/swagger-ui.html"
echo "   Booking Service: http://localhost:8082/swagger-ui.html"
echo
echo "🔍 Health Checks:"
echo "   Event Service:   http://localhost:8081/actuator/health"
echo "   Booking Service: http://localhost:8082/actuator/health"
echo
echo "📝 Logs:"
echo "   Event Service:   tail -f logs/event-service.log"
echo "   Booking Service: tail -f logs/booking-service.log"
echo
echo "🧪 Quick Test:"
echo "   curl http://localhost:8081/api/events/upcoming"
echo
echo "🛑 To stop services:"
echo "   ./stop-services.sh"
echo
echo "═══════════════════════════════════════════════════════"
echo -e "${GREEN}🎉 Ready to test seat hold functionality!${NC}"
echo "═══════════════════════════════════════════════════════"
