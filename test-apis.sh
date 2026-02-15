#!/bin/bash

echo "Testing Event Ticketing Platform APIs"
echo "====================================="
echo

# Test Event Service Health
echo "1. Testing Event Service Health..."
curl -s http://localhost:8081/actuator/health | jq '.status' || echo "Event Service not responding"
echo

# Test Booking Service Health
echo "2. Testing Booking Service Health..."
curl -s http://localhost:8082/actuator/health | jq '.status' || echo "Booking Service not responding"
echo

# Test Event Service APIs
echo "3. Testing Event APIs..."
echo "   - Get upcoming events:"
curl -s "http://localhost:8081/api/events/upcoming" | head -200
echo
echo "   - Get cities:"
curl -s "http://localhost:8081/api/events/cities"
echo
echo "   - Get categories:"
curl -s "http://localhost:8081/api/events/categories"
echo
echo "   - Test seat availability API:"
SEAT_RESPONSE=$(curl -s "http://localhost:8081/api/events/1/seats")
SEAT_COUNT=$(echo "$SEAT_RESPONSE" | jq '.seats | length' 2>/dev/null || echo "0")
AVAILABLE_COUNT=$(echo "$SEAT_RESPONSE" | jq '[.seats[] | select(.status == "AVAILABLE")] | length' 2>/dev/null || echo "0")
HELD_COUNT=$(echo "$SEAT_RESPONSE" | jq '[.seats[] | select(.status == "HELD")] | length' 2>/dev/null || echo "0")
BOOKED_COUNT=$(echo "$SEAT_RESPONSE" | jq '[.seats[] | select(.status == "BOOKED")] | length' 2>/dev/null || echo "0")
echo "   Total seats: $SEAT_COUNT (Available: $AVAILABLE_COUNT, Held: $HELD_COUNT, Booked: $BOOKED_COUNT)"
echo

# Test if database is empty and suggest sample data creation
echo "4. Database Status:"
if curl -s "http://localhost:8081/api/events/upcoming" | grep -q '\[\]' || [ $(curl -s "http://localhost:8081/api/events/upcoming" | wc -c) -lt 5 ]; then
    echo "   Database appears to be empty. Would you like to create sample data? (y/n)"
    read -p "   " create_sample
    if [[ "$create_sample" =~ ^[Yy]$ ]]; then
        echo "   Creating sample event..."
        curl -X POST http://localhost:8081/api/events \
            -H "Content-Type: application/json" \
            -d '{
                "title": "Sample Rock Concert 2026",
                "description": "A great rock concert with amazing artists",
                "category": "Music",
                "city": "New York",
                "venue": "Madison Square Garden",
                "eventDate": "2026-07-15T20:00:00",
                "totalCapacity": 100,
                "basePrice": 150.00,
                "organizerId": 1
            }' | jq . || echo "Failed to create sample event"
        echo
        echo "   Testing again with new data:"
        curl -s "http://localhost:8081/api/events/upcoming" | jq . || echo "No JSON response"
    fi
else
    echo "   Database has data!"
fi

echo
echo "====================================="
echo "API Test Complete!"
echo
echo "Next Steps:"
echo "1. Import the Postman collection: Event_Ticketing_Platform.postman_collection.json"
echo "2. Test the complete booking flow:"
echo "   - Get events"
echo "   - Get available seats"
echo "   - Create seat hold (10-min TTL)"
echo "   - Confirm booking"
echo
echo "Key Features Implemented:"
echo "- Event discovery and search"
echo "- Complete seat layout with real-time status (AVAILABLE, HELD, BOOKED)"
echo "- Seat reservation with 10-minute expiry"
echo "- Distributed locking (Redis)"
echo "- Double booking prevention"
echo "- Health monitoring"
echo "- Redis caching"
echo
echo "Seat Status Types:"
echo "- AVAILABLE: Seat can be selected and booked"
echo "- HELD: Temporarily reserved (10-minute hold)"
echo "- BOOKED: Confirmed and paid"
