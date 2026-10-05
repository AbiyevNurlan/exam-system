<div align="center">

# 🎓 Exam System Backend

[![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=java)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.2-brightgreen?style=for-the-badge&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue?style=for-the-badge&logo=spring)](https://spring.io/reactive)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=for-the-badge&logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red?style=for-the-badge&logo=redis)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue?style=for-the-badge&logo=docker)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

**High-performance, cloud-ready Online Exam System Backend built with Java 21 Virtual Threads and Reactive Programming**

[Features](#-features) • [Architecture](#-architecture) • [Quick Start](#-quick-start) • [API Docs](#-api-endpoints) • [Monitoring](#-monitoring)

</div>

---

## 📋 Overview

This is a **production-grade, microservices-based** online exam platform backend. It is designed to handle thousands of concurrent exam sessions using **Java 21 Virtual Threads** and **Spring WebFlux** reactive stack. The system supports real-time exam updates via WebSocket, distributed caching with Redis, circuit breaking for fault tolerance, and full Prometheus/Grafana monitoring out of the box.

**Key Highlights:**
- ⚡ **Java 21 Virtual Threads** — massively concurrent without thread pool bottlenecks
- 🔄 **Fully Reactive** — Spring WebFlux + R2DBC for non-blocking I/O
- 🔐 **JWT-based Security** — access & refresh token flow with email verification
- 🛡️ **Fraud Detection** — built-in exam session fraud detection service
- 📡 **Real-time Updates** — WebSocket-based live exam notifications
- 📊 **Full Observability** — Prometheus metrics + Grafana dashboards
- 🐳 **Docker Ready** — complete Docker Compose for all environments

---

## ✨ Features

### Authentication & Authorization
- User registration with email verification
- JWT access token + refresh token rotation
- Password reset via email link
- Role-based access control (STUDENT / TEACHER / ADMIN)
- Brute-force protection with Redis-based rate limiting

### Exam Management
- Full CRUD for exams, questions, and answer options
- Multiple question types (MULTIPLE_CHOICE, TRUE_FALSE, etc.)
- Difficulty levels per question
- Timed exam sessions with automatic expiration
- Scheduled exam status transitions

### Exam Taking
- Secure exam session start & submit flow
- Per-question answer tracking
- Real-time result calculation
- Fraud detection (suspicious behavior flagging)
- Automatic grading

### Infrastructure
- API Gateway with load balancing and circuit breaker
- Redis distributed cache for exam data and sessions
- Rate limiting (Bucket4j + Redis)
- Resilience4j circuit breakers
- Centralized structured logging

---

## 🏗️ Architecture

```
                          ┌─────────────────────┐
                          │      Client App      │
                          │  (Web / Mobile / CLI) │
                          └──────────┬──────────┘
                                     │ HTTP / WebSocket
                                     ▼
                    ┌────────────────────────────────┐
                    │         API Gateway :8080       │
                    │  • Routing & Load Balancing     │
                    │  • Rate Limiting (Bucket4j)     │
                    │  • Circuit Breaker (Resilience4j)│
                    │  • JWT Validation Filter         │
                    │  • Request/Response Logging      │
                    └──┬────────┬────────┬────────┬──┘
                       │        │        │        │
              ┌────────▼─┐ ┌────▼────┐ ┌▼──────┐ ┌▼──────────────┐
              │  Auth    │ │  Exam   │ │  WS   │ │  Monitoring   │
              │ Service  │ │ Service │ │Service│ │   Service     │
              │ :8081    │ │ :8082   │ │ :8084 │ │   :8083       │
              └────┬─────┘ └────┬────┘ └───┬───┘ └───────┬───────┘
                   │            │           │             │
            ┌──────▼────────────▼───────────▼─────────────▼──────┐
            │               Shared Infrastructure                  │
            │  PostgreSQL :5432    Redis :6379    Prometheus :9090  │
            │  (R2DBC reactive)   (Cache/Sessions)  Grafana :3000   │
            └──────────────────────────────────────────────────────┘
```

### Microservices Breakdown

| Service | Port | Responsibility |
|---------|------|----------------|
| **API Gateway** | 8080 | Single entry point, routing, rate limiting, circuit breaking |
| **Auth Service** | 8081 | Registration, login, JWT issuance, password reset, email verify |
| **Exam Service** | 8082 | Exam CRUD, question management, session handling, fraud detection |
| **Monitoring Service** | 8083 | Health aggregation, metrics collection, dashboard data |
| **WebSocket Service** | 8084 | Real-time exam events and notifications |
| **Common Module** | — | Shared DTOs, entities, security utilities, exception handling |

---

## 🛠️ Technology Stack

### Core
| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Language + Virtual Threads |
| Spring Boot | 3.2.2 | Application framework |
| Spring WebFlux | 6.x | Reactive HTTP layer |
| Spring Cloud Gateway | 2023.0.0 | API Gateway |
| Maven | 3.9+ | Build & dependency management |

### Database & Persistence
| Technology | Version | Purpose |
|-----------|---------|---------|
| PostgreSQL | 16 | Primary relational database |
| Spring Data R2DBC | — | Reactive database access |
| Redis | 7 | Caching, sessions, rate limiting |

### Security
| Technology | Version | Purpose |
|-----------|---------|---------|
| JJWT | 0.12.3 | JWT token generation & validation |
| Spring Security | 6.x | Security filter chain |
| BCrypt | — | Password hashing |

### Resilience & Performance
| Technology | Version | Purpose |
|-----------|---------|---------|
| Resilience4j | 2.1.0 | Circuit breaker, retry, rate limiter |
| Bucket4j | 8.7.0 | Token-bucket rate limiting |

### Monitoring & Observability
| Technology | Version | Purpose |
|-----------|---------|---------|
| Micrometer | 1.12.2 | Metrics collection |
| Prometheus | — | Metrics storage |
| Grafana | — | Metrics visualization |
| Spring Actuator | — | Health & info endpoints |

### Developer Tools
| Technology | Version | Purpose |
|-----------|---------|---------|
| Lombok | 1.18.30 | Boilerplate reduction |
| MapStruct | 1.5.5 | DTO mapping |
| SpringDoc OpenAPI | 2.3.0 | Swagger UI / API docs |
| Testcontainers | 1.19.3 | Integration testing |

---

## 📁 Project Structure

```
exam-system/
├── 📦 common/                        # Shared library module
│   └── src/main/java/com/examportal/common/
│       ├── config/                   # Jackson, R2DBC, Redis, VirtualThread configs
│       ├── domain/                   # JPA/R2DBC entities (User, Exam, Question, etc.)
│       ├── dto/                      # Request/Response DTOs
│       ├── exception/                # Global exception handler + custom exceptions
│       ├── security/                 # JWT provider, auth filter, UserPrincipal
│       └── utils/                    # CodeGenerator, DateTimeUtils
│
├── 🚪 api-gateway/                   # Spring Cloud Gateway
│   └── src/main/java/com/examportal/gateway/
│       ├── config/                   # Route definitions, rate limiter config
│       ├── filter/                   # LoggingFilter
│       └── controller/               # FallbackController
│
├── 🔐 auth-service/                  # Authentication microservice
│   └── src/main/java/com/examportal/auth/
│       ├── config/                   # PasswordEncoder, SecurityConfig
│       ├── controller/               # AuthController (REST endpoints)
│       ├── service/                  # AuthService, TokenService
│       ├── repository/               # UserRepo, RefreshTokenRepo, PasswordResetRepo
│       └── exception/                # DuplicateEmail, InvalidCredentials, etc.
│
├── 📝 exam-service/                  # Core exam microservice
│   └── src/main/java/com/examportal/exam/
│       ├── config/                   # Performance, RateLimit, Resilience, Security configs
│       ├── controller/               # ExamController
│       ├── service/                  # ExamService, CacheService, SchedulerService
│       │                             # AnswerSubmissionService, FraudDetectionService
│       ├── repository/               # Exam, Question, Option, Session, Answer repos
│       └── mapper/                   # ExamMapper (MapStruct)
│
├── 📊 monitoring-service/            # Observability microservice
│   └── src/main/java/com/examportal/monitoring/
│
├── 📡 websocket-service/             # Real-time WebSocket microservice
│   └── src/main/java/com/examportal/websocket/
│       ├── config/                   # WebSocketConfig
│       └── handler/                  # ExamWebSocketHandler
│
├── 🐳 Docker Infrastructure
│   ├── docker-compose.yml            # Development environment
│   ├── docker-compose.production.yml # Production environment
│   ├── docker-compose.performance.yml# Performance testing
│   ├── Dockerfile.auth               # Auth Service image
│   ├── Dockerfile.exam               # Exam Service image
│   ├── Dockerfile.gateway            # API Gateway image
│   ├── Dockerfile.monitoring         # Monitoring Service image
│   └── Dockerfile.websocket          # WebSocket Service image
│
├── 🗄️ Database Scripts
│   └── init-scripts/                 # Run by Postgres on first start (alphabetical order)
│       ├── 01-create-databases.sh    # Creates exam_auth, exam_db, exam_monitoring
│       ├── 02-auth.sql               # Auth service schema (exam_auth)
│       ├── 03-exam.sql               # Exam service schema (exam_db)
│       └── 04-monitoring.sql         # Monitoring database (no tables yet)
│
├── 📈 Monitoring Config
│   ├── prometheus/prometheus.yml     # Prometheus scrape config
│   └── nginx/nginx.conf              # Nginx reverse proxy config
│
└── pom.xml                           # Parent Maven POM (multi-module)
```

---

## 🚀 Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java JDK | 21+ | Must support Virtual Threads |
| Maven | 3.9+ | For building modules |
| Docker | Latest | For infrastructure |
| Docker Compose | Latest | Orchestrates all containers |

### Option A — Full Docker (Recommended)

Everything runs in containers, no local Java needed:

```bash
# Clone the repository
git clone https://github.com/AbiyevNurlan/exam-system.git
cd exam-system

# Start ALL services (infra + applications)
docker-compose up -d

# Check all services are healthy
docker-compose ps
```

### Option B — Local Development

Run infrastructure in Docker, services locally:

```bash
# Step 1: Start only infrastructure
docker-compose up -d postgres redis prometheus grafana

# Step 2: Databases and schemas are created automatically by init-scripts/ on the first
# start of an empty Postgres volume. To re-run them: docker-compose down -v (deletes data)

# Step 3: Build the whole project
mvn clean install -DskipTests

# Step 4: Run services (each in a separate terminal)
cd api-gateway    && mvn spring-boot:run
cd auth-service   && mvn spring-boot:run
cd exam-service   && mvn spring-boot:run
cd monitoring-service && mvn spring-boot:run
cd websocket-service  && mvn spring-boot:run
```

### Windows Quick Start

```bat
start-all-services.bat
```

---

## ⚙️ Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `JWT_SECRET` | *(required, no default)* | Base64-encoded JWT signing secret, at least 32 bytes. For docker-compose copy `.env.example` to `.env` |
| `JWT_ACCESS_EXPIRY` | `900` | Access token TTL (seconds) |
| `JWT_REFRESH_EXPIRY` | `604800` | Refresh token TTL (seconds) |

### Virtual Threads (Java 21)

Enabled by default in all services:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

---

## 📡 API Endpoints

All requests go through the **API Gateway at port 8080**.

### Authentication — `/api/v1/auth`

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/register` | ❌ | Register a new user |
| `POST` | `/login` | ❌ | Login and receive JWT tokens |
| `POST` | `/refresh` | ❌ | Refresh access token |
| `POST` | `/logout` | ✅ | Invalidate refresh token |
| `POST` | `/forgot-password` | ❌ | Request password reset email |
| `POST` | `/reset-password` | ❌ | Reset password with token |
| `GET` | `/verify-email` | ❌ | Verify email address |

**Example — Register:**
```json
POST /api/v1/auth/register
{
  "username": "student1",
  "email": "student1@example.com",
  "password": "SecurePass123!",
  "role": "STUDENT"
}
```

**Example — Login Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzM4N...",
  "refreshToken": "dGhpcyBpcyBhIHJlZnJlc2g...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Exams — `/api/v1/exams`

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/` | ✅ | List all available exams |
| `GET` | `/{id}` | ✅ | Get exam details |
| `POST` | `/` | ✅ TEACHER | Create a new exam |
| `PUT` | `/{id}` | ✅ TEACHER | Update exam |
| `DELETE` | `/{id}` | ✅ ADMIN | Delete exam |
| `POST` | `/{id}/start` | ✅ STUDENT | Start exam session |
| `POST` | `/{id}/submit` | ✅ STUDENT | Submit exam answers |
| `GET` | `/{id}/results` | ✅ | Get exam results |

### Questions — `/api/v1/exams/{examId}/questions`

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/` | ✅ | List questions for exam |
| `POST` | `/` | ✅ TEACHER | Add question to exam |
| `PUT` | `/{questionId}` | ✅ TEACHER | Update question |
| `DELETE` | `/{questionId}` | ✅ TEACHER | Remove question |

---

## 📚 API Documentation (Swagger UI)

Once running, access interactive API docs:

| Service | URL |
|---------|-----|
| Auth Service | http://localhost:8081/swagger-ui.html |
| Exam Service | http://localhost:8082/swagger-ui.html |
| Monitoring Service | http://localhost:8083/swagger-ui.html |

---

## 📊 Monitoring

| Tool | URL | Credentials |
|------|-----|-------------|
| **Prometheus** | http://localhost:9090 | No auth |
| **Grafana** | http://localhost:3000 | admin / admin |
| **Actuator Health** | http://localhost:808x/actuator/health | No auth |
| **Actuator Metrics** | http://localhost:808x/actuator/metrics | No auth |

---

## 🔒 Security Design

### JWT Token Flow

```
Client                API Gateway            Auth Service
  │                       │                       │
  │── POST /auth/login ──▶│── forward ──────────▶ │
  │                       │                       │── validate credentials
  │                       │                       │── generate access + refresh tokens
  │◀─ { accessToken,     ◀│◀── JWT response ──── │
  │     refreshToken }     │                       │
  │                        │                       │
  │── GET /exams ─────────▶│
  │   Authorization: Bearer {accessToken}
  │                        │── validate JWT locally (no roundtrip to auth)
  │                        │── forward to exam-service
  │◀─ exam data ──────────◀│
```

### Rate Limiting

All endpoints are rate-limited per IP using **Bucket4j + Redis**:
- Public endpoints: 20 requests/minute
- Auth endpoints: 10 requests/minute (brute-force protection)
- Authenticated endpoints: 100 requests/minute

### Circuit Breaker States

```
CLOSED ──(failure threshold exceeded)──▶ OPEN
  ▲                                         │
  │                                    (wait period)
  │                                         ▼
  └────────(success)─────────────── HALF_OPEN
```

---

## ⚡ Performance

### Virtual Threads vs Platform Threads

Java 21 Virtual Threads allow **millions of concurrent lightweight threads** using carrier threads from the JVM. This eliminates traditional thread-pool bottlenecks:

```
Traditional Server (200 threads):
  Thread ──── blocked on DB ──── CPU idle ──── wasted

Virtual Thread Server:
  Carrier Thread: ──active──▶ mount ──▶ unmount ──▶ mount ──▶ unmount
                                 ↑                       ↑
                             vt-1 runs                vt-2 runs
```

### Benchmarks (Docker, 4 cores, 8GB RAM)

| Scenario | Requests/sec | Avg Latency | P99 Latency |
|----------|-------------|-------------|-------------|
| Auth (login) | ~2,500 rps | 8ms | 45ms |
| Exam list (cached) | ~8,000 rps | 3ms | 12ms |
| Exam submit | ~1,200 rps | 18ms | 65ms |

---

## 🗄️ Database Schema

### Core Entities

```
users ──────────────────────────────────────────┐
  id, username, email, password_hash             │
  role (STUDENT/TEACHER/ADMIN)                  │
  email_verified, created_at                    │
                                                │
exams ──────────────────────────────────────────┤
  id, title, description, duration_minutes      │
  difficulty, status, created_by (FK→users)     │
  start_time, end_time                          │
                                                │
questions ──────────────────────────────────────┤
  id, exam_id (FK→exams), text                  │
  type (MULTIPLE_CHOICE/TRUE_FALSE)             │
  points, difficulty_level                      │
                                                │
question_options ────────────────────────────────┤
  id, question_id (FK→questions)               │
  text, is_correct                              │
                                                │
exam_sessions ──────────────────────────────────┤
  id, exam_id (FK→exams), user_id (FK→users)   │
  status (IN_PROGRESS/COMPLETED/EXPIRED)       │
  started_at, submitted_at, score              │
                                                │
user_answers ───────────────────────────────────┘
  id, session_id (FK→exam_sessions)
  question_id (FK→questions)
  selected_option_id (FK→question_options)
```

---

## 🐳 Docker Environments

### Development (`docker-compose.yml`)
Runs full stack — PostgreSQL, Redis, Prometheus, Grafana, all services.

### Production (`docker-compose.production.yml`)
Adds Nginx reverse proxy, health checks, resource limits, restart policies.

### Performance Testing (`docker-compose.performance.yml`)
Optimized resource allocation for load testing scenarios.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m 'Add some feature'`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

**Nurlan Abiyev**
- GitHub: [@AbiyevNurlan](https://github.com/AbiyevNurlan)
- Email: nurlanabiyev25@gmail.com

---

<div align="center">

⭐ If you found this project useful, please give it a star!

</div>
