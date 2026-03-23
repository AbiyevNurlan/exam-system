# Exam System Backend

High-performance Java 21 exam system backend with Virtual Threads and reactive programming.

## Technology Stack

- **Java 21** with Virtual Threads enabled
- **Spring Boot 3.2.2** with WebFlux (reactive)
- **Maven** multi-module project
- **PostgreSQL** with R2DBC (reactive driver)
- **Redis** for caching and rate limiting
- **JWT** for authentication
- **Lombok** & **MapStruct** for boilerplate reduction

## Project Structure

```
exam-system/
├── api-gateway/          # API Gateway with routing and rate limiting
├── auth-service/         # Authentication and authorization
├── exam-service/         # Core exam management
├── monitoring-service/   # Centralized monitoring and metrics
├── websocket-service/    # Real-time communication
├── common/               # Shared components
│   ├── domain/          # Domain entities and DTOs
│   ├── security/        # JWT and security utilities
│   ├── utils/           # Utility classes
│   └── config/          # Common configurations
└── pom.xml              # Parent POM
```

## Services and Ports

| Service             | Port | Description                          |
|---------------------|------|--------------------------------------|
| API Gateway         | 8080 | Central entry point, routing         |
| Auth Service        | 8081 | Authentication, JWT management       |
| Exam Service        | 8082 | Exam CRUD, questions, results        |
| Monitoring Service  | 8083 | Metrics aggregation, health checks   |
| WebSocket Service   | 8084 | Real-time exam updates               |

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose
- PostgreSQL 16+ (or use Docker)
- Redis 7+ (or use Docker)

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- PostgreSQL (port 5432)
- Redis (port 6379)
- Prometheus (port 9090)
- Grafana (port 3000)

### 2. Build the Project

```bash
mvn clean install -DskipTests
```

### 3. Run Services

Run each service in a separate terminal:

```bash
# API Gateway
cd api-gateway && mvn spring-boot:run

# Auth Service
cd auth-service && mvn spring-boot:run

# Exam Service
cd exam-service && mvn spring-boot:run

# Monitoring Service
cd monitoring-service && mvn spring-boot:run

# WebSocket Service
cd websocket-service && mvn spring-boot:run
```

## Configuration

### Environment Variables

| Variable      | Default   | Description            |
|---------------|-----------|------------------------|
| DB_HOST       | localhost | PostgreSQL host        |
| DB_PORT       | 5432      | PostgreSQL port        |
| DB_USERNAME   | postgres  | Database username      |
| DB_PASSWORD   | postgres  | Database password      |
| REDIS_HOST    | localhost | Redis host             |
| REDIS_PORT    | 6379      | Redis port             |
| JWT_SECRET    | (default) | JWT signing secret key |

### Virtual Threads

Virtual Threads are enabled by default in `application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

## API Documentation

Once services are running, access Swagger UI:

- Auth Service: http://localhost:8081/swagger-ui.html
- Exam Service: http://localhost:8082/swagger-ui.html
- Monitoring Service: http://localhost:8083/swagger-ui.html

## Monitoring

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)
- **Actuator endpoints**: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`

## API Endpoints

### Authentication (`/api/v1/auth`)

| Method | Endpoint          | Description           |
|--------|-------------------|-----------------------|
| POST   | /register         | Register new user     |
| POST   | /login            | User login            |
| POST   | /refresh          | Refresh access token  |
| POST   | /logout           | User logout           |
| POST   | /forgot-password  | Request password reset|
| POST   | /reset-password   | Reset password        |
| GET    | /verify-email     | Verify email address  |

### Exams (`/api/v1/exams`)

| Method | Endpoint              | Description            |
|--------|-----------------------|------------------------|
| GET    | /                     | List all exams         |
| GET    | /{id}                 | Get exam by ID         |
| POST   | /                     | Create new exam        |
| PUT    | /{id}                 | Update exam            |
| DELETE | /{id}                 | Delete exam            |
| POST   | /{id}/start           | Start exam attempt     |
| POST   | /{id}/submit          | Submit exam attempt    |
| GET    | /{id}/results         | Get exam results       |

## Architecture Features

### Virtual Threads
Leverages Java 21 Virtual Threads for high-throughput concurrent operations without the overhead of platform threads.

### Reactive Programming
Uses Spring WebFlux and R2DBC for non-blocking, reactive data access.

### Circuit Breaker
Implements Resilience4j circuit breakers in the API Gateway for fault tolerance.

### Rate Limiting
Redis-based rate limiting in the API Gateway to protect services from overload.

### Distributed Caching
Redis for caching exam data, user sessions, and rate limit counters.

## License

MIT License
