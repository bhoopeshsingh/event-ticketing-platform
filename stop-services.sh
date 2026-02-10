#!/bin/bash

# Event Ticketing Platform - Stop Services Script

set -e

echo "🛑 Stopping Event Ticketing Platform Services"
echo "============================================="
echo

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Function to stop service by PID file
stop_service() {
    local name=$1
    local pid_file=$2

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            echo -e "${YELLOW}Stopping $name (PID: $pid)...${NC}"
            kill $pid 2>/dev/null || true
            sleep 2

            # Force kill if still running
            if ps -p $pid > /dev/null 2>&1; then
                echo -e "${YELLOW}Force stopping $name...${NC}"
                kill -9 $pid 2>/dev/null || true
            fi

            echo -e "${GREEN}✅ $name stopped${NC}"
        else
            echo -e "${YELLOW}⚠️  $name is not running${NC}"
        fi
        rm -f "$pid_file"
    else
        echo -e "${YELLOW}⚠️  No PID file found for $name${NC}"
    fi
}

# Function to kill processes by port
kill_by_port() {
    local port=$1
    local name=$2

    local pids=$(lsof -ti:$port 2>/dev/null || true)
    if [ ! -z "$pids" ]; then
        echo -e "${YELLOW}Stopping processes on port $port ($name)...${NC}"
        echo "$pids" | xargs kill -9 2>/dev/null || true
        echo -e "${GREEN}✅ Processes on port $port stopped${NC}"
    fi
}

# Stop services using PID files
if [ -d "logs" ]; then
    stop_service "Event Service" "logs/event-service.pid"
    stop_service "Booking Service" "logs/booking-service.pid"
else
    echo -e "${YELLOW}⚠️  No logs directory found${NC}"
fi

# Fallback: kill by port
echo
echo "🔍 Checking for running services on ports..."
kill_by_port 8081 "Event Service"
kill_by_port 8082 "Booking Service"

# Kill any remaining Maven processes for our services
echo
echo "🧹 Cleaning up Maven processes..."
pkill -f "event-service" 2>/dev/null || true
pkill -f "booking-service" 2>/dev/null || true

sleep 2

echo
echo "═══════════════════════════════════════════"
echo -e "${GREEN}✅ All services stopped${NC}"
echo "═══════════════════════════════════════════"
echo
echo "📝 Logs are preserved in: logs/"
echo
echo "🔄 To restart services:"
echo "   ./start-services.sh"
echo
