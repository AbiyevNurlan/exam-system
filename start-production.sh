#!/bin/bash
# =====================================================
# EXAM PORTAL - PRODUCTION START SCRIPT
# For Linux/Mac
# =====================================================

echo "============================================"
echo "   EXAM PORTAL - PRODUCTION DEPLOYMENT"
echo "   Optimized for 100K Concurrent Users"
echo "============================================"

# Check Docker
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker is not installed"
    exit 1
fi

# Set environment variables
# Secrets are never hardcoded: they come from the environment or the git-ignored .env file (see .env.example)
for var in JWT_SECRET DB_PASSWORD GRAFANA_PASSWORD; do
    if [ -z "${!var}" ] && [ -f .env ]; then
        value=$(grep -E "^${var}=" .env | tail -n 1 | cut -d= -f2- | tr -d '\r')
        if [ -n "$value" ] && [ "$value" != "CHANGE_ME" ]; then
            export "$var=$value"
        fi
    fi
    if [ -z "${!var}" ]; then
        echo "ERROR: $var is not set."
        echo "       Copy .env.example to .env and replace every CHANGE_ME value"
        echo "       (JWT_SECRET, DB_PASSWORD, GRAFANA_PASSWORD)."
        exit 1
    fi
    export "$var"
done

# Build and start
echo ""
echo "[1/4] Building Java applications..."
./mvnw clean package -DskipTests -q

echo ""
echo "[2/4] Building Docker images..."
docker-compose -f docker-compose.production.yml build --parallel

echo ""
echo "[3/4] Starting infrastructure (PostgreSQL, Redis)..."
docker-compose -f docker-compose.production.yml up -d postgres-primary redis-master
sleep 30

echo ""
echo "[4/4] Starting all services..."
docker-compose -f docker-compose.production.yml up -d

echo ""
echo "============================================"
echo "   DEPLOYMENT COMPLETE"
echo "============================================"
echo ""
echo "Services:"
echo "  - NGINX Load Balancer: http://localhost:80"
echo "  - API Gateway 1:       http://localhost:8080"
echo "  - API Gateway 2:       http://localhost:8081"
echo "  - Auth Service x2:     Internal"
echo "  - Exam Service x3:     Internal"
echo "  - PostgreSQL:          localhost:5432"
echo "  - Redis:               localhost:6379"
echo "  - Prometheus:          http://localhost:9090"
echo "  - Grafana:             http://localhost:3000"
echo ""
echo "Monitoring:"
echo "  - Health: curl http://localhost/health"
echo "  - Metrics: curl http://localhost:8080/actuator/prometheus"
echo ""
echo "============================================"
