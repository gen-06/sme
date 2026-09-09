#!/bin/sh
# Curl-based equivalent of the Postman collection, for demoing the full
# register -> data-source -> sync -> score flow without Postman.
#
# Usage: API_KEY=<key from seed log> ./scripts/demo.sh [base_url]
# The demo API key is printed once at startup when running with
# SPRING_PROFILES_ACTIVE=seed (see README).

set -e

BASE_URL="${2:-${BASE_URL:-http://localhost:8080/api/v1}}"
API_KEY="${API_KEY:?Set API_KEY to the key logged by SeedDataRunner on startup}"

echo "=== Registering business ==="
BUSINESS=$(curl -s -X POST "$BASE_URL/businesses" \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"name":"Demo Script Business","country":"KE","industry":"Retail"}')
echo "$BUSINESS"
BUSINESS_ID=$(echo "$BUSINESS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "businessId=$BUSINESS_ID"

echo
echo "=== Adding mobile-money data source ==="
curl -s -X POST "$BASE_URL/businesses/$BUSINESS_ID/data-sources" \
  -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  -d '{"adapterType":"MOBILE_MONEY","provider":"M-PESA"}'
echo

echo
echo "=== Sync (1st, expect inserts) ==="
curl -s -X POST "$BASE_URL/businesses/$BUSINESS_ID/sync" -H "X-API-Key: $API_KEY"
echo

echo
echo "=== Sync (2nd, expect zero new inserts) ==="
curl -s -X POST "$BASE_URL/businesses/$BUSINESS_ID/sync" -H "X-API-Key: $API_KEY"
echo

echo
echo "=== Latest score ==="
curl -s "$BASE_URL/businesses/$BUSINESS_ID/score" -H "X-API-Key: $API_KEY"
echo

echo
echo "=== Score history ==="
curl -s "$BASE_URL/businesses/$BUSINESS_ID/score/history" -H "X-API-Key: $API_KEY"
echo

echo
echo "=== Transactions (first page) ==="
curl -s "$BASE_URL/businesses/$BUSINESS_ID/transactions?size=5" -H "X-API-Key: $API_KEY"
echo
