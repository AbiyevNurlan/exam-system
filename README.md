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
│   ├── init-scripts/                 # Run by Postgres on first start of an empty volume
│   │   └── 01-create-databases.sh    # Creates the empty databases exam_auth, exam_db, exam_monitoring
│   ├── auth-service/src/main/resources/db/migration/   # Flyway migrations for exam_auth (V1__baseline.sql, ...)
│   └── exam-service/src/main/resources/db/migration/   # Flyway migrations for exam_db (V1__baseline.sql, ...)
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

# Step 2: Nothing to do by hand. The empty databases are created by init-scripts/ on the first
# start of an empty Postgres volume; tables are created and upgraded by Flyway when
# auth-service and exam-service start (see "Database safety" below).

# Step 3: Build the whole project
mvn clean install -DskipTests

# Step 4: Run services (each in a separate terminal)
cd api-gateway    && mvn spring-boot:run
cd auth-service   && mvn spring-boot:run
cd exam-service   && mvn spring-boot:run
cd monitoring-service && mvn spring-boot:run
cd websocket-service  && mvn spring-boot:run
```

### Starting the stack (Windows)

Start the stack with the script, from any folder:

```powershell
.\scripts\up.ps1
```

It runs `scripts\check-env.ps1` first, which checks that `.env` has every setting `docker-compose.yml`
requires (the key names are printed, never a value) and that `JWT_SECRET` and `OTP_HMAC_SECRET` are base64 of
at least 32 bytes. Only when that passes does it run `docker-compose up -d`, always with this
repository's compose file and `.env`. It never stops or removes anything.

- **`docker-compose down -v` is always forbidden**: it deletes the database and media volumes (see
  "Database safety").
- Start the stack from this folder only. Another copy of the project started with `docker-compose up`
  recreates the same containers with that copy's settings; services then fail with messages such as
  `OTP_HMAC_SECRET is not set` although `.env` here is complete. `scripts\up.ps1` repairs that.
- Every service has `restart: unless-stopped`, so the stack comes back after a Docker restart.

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
| `OTP_HMAC_SECRET` | *(required, no default)* | auth-service only. Base64 of at least 32 random bytes; email codes (verification, password reset) are stored only as an HMAC under this key. Use a different value than `JWT_SECRET` |
| `INTERNAL_SERVICE_SECRET` | *(empty = switched off)* | At least 32 characters, shared by auth-service and exam-service only. Protects their service-to-service endpoints (`/internal/**`). Without it adding group members, the CSV import and member names do not work |
| `MAIL_HOST` / `MAIL_PORT` | *(empty)* / `587` (`docker-compose.yml`: `mailpit` / `1025`) | SMTP server for outgoing email (verification and password reset codes). Required in production |
| `MAIL_SMTP_AUTH` | `true` (`docker-compose.yml`: `false`) | Log in with `MAIL_USERNAME` / `MAIL_PASSWORD` |
| `MAIL_STARTTLS` / `MAIL_SSL` | `true` / `false` (`docker-compose.yml`: both `false`) | Transport security: STARTTLS on port 587, or implicit TLS on port 465. Never both |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | *(empty)* | SMTP credentials |
| `MAIL_FROM` | *(empty)* | Sender address of every email; its domain needs SPF/DKIM at the provider. Required for every host but Mailpit: auth-service refuses to start without it |
| `MAIL_FROM_NAME` | `Exam System` | Display name of the sender |
| `MAIL_REPLY_TO` | *(empty)* | Optional Reply-To address of every email |
| `MEDIA_S3_ACCESS_KEY` / `MEDIA_S3_SECRET_KEY` | *(required by `docker-compose.yml`, no default)* | Credentials of the object storage that holds question media. Letters and digits only |
| `MEDIA_S3_PUBLIC_ENDPOINT` | `http://localhost:8333` | The storage address as the **browser** reaches it; it is the host inside every presigned URL |
| `MEDIA_CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Frontend origins that may upload/download directly at the storage (comma separated) |

All of these are described in `.env.example`; copy it to `.env` (git-ignored) and replace every `CHANGE_ME`.

### Email in development (Mailpit)

`docker-compose.yml` starts [Mailpit](https://mailpit.axllent.org/), a local SMTP server that catches every
email and delivers nothing. Without `MAIL_HOST` in `.env` the development stack sends to it. Open
<http://localhost:8025> to read the verification and password reset codes. Production
(`docker-compose.production.yml`) has no Mailpit and needs the `MAIL_*` variables of a real SMTP provider.

### Real email

Verification and password reset codes leave the system over plain SMTP, so every provider with an SMTP
endpoint works and none is built in. Each email has a plain-text and an HTML part with the same content, no
links and nothing that tracks the reader. A failed send is tried 3 times (1 s and 3 s apart) in the
background and never fails the request; `mail_sent_total` and `mail_failed_total` (auth-service,
`/actuator/prometheus`) count emails, and the log shows the masked address, the attempt and the error type.

**Provider.** Use a transactional email service (for example Amazon SES, Postmark, Mailgun, Brevo or
SendGrid), not a personal mailbox. What to look for: SMTP access with a login per application, DKIM signing
for your own domain, a sending quota above the largest exam cohort, and a list of bounces and complaints.
Codes are valid for 10 minutes, so prefer a provider that delivers transactional email within seconds.

**Settings** (`.env`, see `.env.example`): `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`
(an address of your domain, e.g. `no-reply@your-domain`), optionally `MAIL_FROM_NAME` and `MAIL_REPLY_TO`,
and one transport:

| Port | Switches | Meaning |
|------|----------|---------|
| `587` | `MAIL_STARTTLS=true`, `MAIL_SSL=false` | STARTTLS: the usual choice. It is required, never skipped |
| `465` | `MAIL_SSL=true`, `MAIL_STARTTLS=false` | TLS from the first byte |

`MAIL_SMTP_AUTH=true` for every real provider. auth-service checks the combination at startup and refuses
to start with a message that names the variable (no `MAIL_FROM` for a real host, both switches true, login
without credentials). Apply a change with `docker-compose up -d auth-service`, then send one email through
the real registration flow:

```powershell
.\scripts\send-test-mail.ps1 -To you@your-mailbox.example                  # verification code
.\scripts\send-test-mail.ps1 -To you@your-mailbox.example -PasswordReset   # for an already verified address
```

The script prints the HTTP status and the outcome from the auth-service log, never a code or a credential.

**DNS for the sender domain.** Without these three records codes land in spam or are rejected:

- **SPF**: a TXT record on the domain that lists the provider as an allowed sender
  (`v=spf1 include:<provider> -all`). One SPF record per domain; add the provider to an existing one.
- **DKIM**: the provider signs every email; publish the public keys it gives you (CNAME or TXT records
  under `<selector>._domainkey`).
- **DMARC**: a TXT record on `_dmarc.<domain>`. Start with `v=DMARC1; p=none; rua=mailto:<reports address>`,
  read the reports, then move to `quarantine` and `reject`. DMARC passes only when the domain of
  `MAIL_FROM` matches the SPF or DKIM domain, so send from your own domain, not from the provider's.

**Warm-up and limits.** A new domain or account has no reputation: begin with a small volume and raise it
over days, and tell the provider before the first exam with hundreds of registrations at once. New accounts
are often in a sandbox (verified recipients only, a low daily quota) until the provider lifts it. Know the
quota per day and per second: above it the provider rejects sends, which shows as `mail_failed_total` and
as users without a code. The application itself sends at most 5 codes per address per hour.

**Why not Gmail SMTP.** `smtp.gmail.com` with an app password is fine for a handful of test emails and
nothing more: about 500 recipients per day, the From address is rewritten to the Gmail account, bulk-like
automated sending can lock the account, and there is no DKIM for your domain and no bounce reporting.

**Back to Mailpit.** Comment out the whole `MAIL_*` block in `.env` (all of it, also `MAIL_PORT` and the
switches) and run `docker-compose up -d auth-service`. Nothing else changes; emails are caught at
<http://localhost:8025> again.

Still open (see `docs/BACKLOG.md`): the DNS records on the production domain, bounce and complaint
handling, and an outbox so that a code survives a restart or a longer outage of the mail server.

### Question media storage (SeaweedFS)

Images, audio and video of questions and options live in S3-compatible object storage. In development
`docker-compose.yml` starts [SeaweedFS](https://github.com/seaweedfs/seaweedfs) (`exam-media-storage`, S3 API
on `127.0.0.1:8333` only, volume `exam-system_media_data`). Files never pass through a service:

1. The admin panel asks `POST /api/media/upload-url` and gets a presigned URL.
2. The browser PUTs the file **directly to the storage** with exactly the returned headers.
3. `POST /api/media/{id}/confirm` checks the stored file (size, magic bytes) and makes it usable.
4. Participants get a 5-minute presigned GET URL per media, only inside their own running session.

Two things must match the browser's point of view, otherwise uploads fail:

- **`MEDIA_S3_PUBLIC_ENDPOINT`** is the host written into presigned URLs. The signature covers the host, so
  it must be exactly the address the browser uses (in production: the public HTTPS address of the storage).
  exam-service itself talks to the storage over the internal address (`MEDIA_S3_ENDPOINT`).
- **`MEDIA_CORS_ORIGINS`** lists the frontend origins. exam-service writes them to the bucket as CORS rules
  at every start (GET, HEAD, PUT); an origin that is not listed is refused by the storage.

The bucket (`exam-media`) is private: exam-service creates it at startup and nothing sets a public policy;
without a valid signature every request is a 403. Without the storage settings exam-service still starts
and the media endpoints answer 503 `MEDIA_STORAGE_UNAVAILABLE`. `docker-compose.production.yml` has no
storage service yet (see `docs/BACKLOG.md`).

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
| `POST` | `/register` | ❌ | Register; emails a 6-digit code, returns no tokens |
| `POST` | `/verify-otp` | ❌ | Verify the email with the code and receive JWT tokens |
| `POST` | `/resend-otp` | ❌ | Send a new verification code |
| `POST` | `/login` | ❌ | Login and receive JWT tokens |
| `POST` | `/refresh` | ❌ | Refresh access token |
| `POST` | `/logout` | ✅ | Invalidate refresh token |
| `POST` | `/forgot-password` | ❌ | Email a 6-digit password reset code |
| `POST` | `/reset-password` | ❌ | Set a new password with `{email, code, newPassword}` |

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

## 🛡️ Database safety

The databases hold real user accounts, exams and results. Treat the Postgres volume as irreplaceable.
The same goes for the media volume (`exam-system_media_data`): it holds every uploaded question image,
audio and video file.

### Never do this

```bash
docker-compose down -v      # deletes the Postgres AND media volumes = every user, exam, result and media file
docker volume rm ...        # same
```

`docker-compose down` (without `-v`), `docker-compose up -d --build` and restarting single services are
safe: data lives in a named volume that survives them.

| Compose file | Postgres volume |
|---|---|
| `docker-compose.yml` | `exam-system_postgres_data` |
| `docker-compose.production.yml` | `exam-system-prod_postgres_data` |
| `docker-compose.performance.yml` | `exam-system-perf_postgres_data` |

### Schema changes (Flyway)

`init-scripts/` only creates the empty databases. Tables are owned by Flyway migrations that run when a
service starts, before it accepts requests:

| Database | Migrations |
|---|---|
| `exam_auth` | `auth-service/src/main/resources/db/migration/` |
| `exam_db` | `exam-service/src/main/resources/db/migration/` |

To change the schema:

1. Add a NEW file with the next version number, e.g. `V2__add_exam_tags.sql` (two underscores).
2. Never edit, rename or delete a migration that has already been applied anywhere. Flyway stores a
   checksum of every applied file and the service refuses to start if one differs.
3. Write migrations that keep existing rows valid (new columns nullable or with a default).
4. Take a backup, then rebuild and restart the service: `docker-compose up -d --build exam-service`.
   If the migration fails, the service stops with the Flyway error in its log
   (`docker-compose logs exam-service`) and the previous schema is left in place.

Applied versions: `docker exec exam-postgres psql -U postgres -d exam_db -c "SELECT version, description, success FROM flyway_schema_history"`.

### Backup and restore

```powershell
.\scripts\backup.ps1          # pg_dump of exam_auth, exam_db, exam_monitoring plus the media bucket into .\backups\<yyyyMMdd-HHmm>\ (keeps the last 14)
.\scripts\restore-test.ps1    # restores the latest backup into temporary databases and a temporary bucket, compares row and object counts
```

The media bucket is copied object by object through the S3 API (an `amazon/aws-cli` container) into the
`media` subfolder; every object has its own line in `SHA256SUMS.txt`. A storage container that is not
running fails the backup; `-SkipMedia` on both scripts skips media on purpose. To put media back, sync the
folder into the bucket the same way (`aws s3 sync <backup>\media s3://exam-media --endpoint-url ...`).

Run `backup.ps1` on a schedule (e.g. Windows Task Scheduler, daily) and before every deployment that adds
a migration. Run `restore-test.ps1` regularly: a backup that was never restored is not a backup.
For the production compose file pass `-Container exam-postgres-primary` to both scripts.

Restoring into a live database is deliberately a manual step (stop the services that use it first):

```powershell
docker cp .\backups\<stamp>\exam_db.dump exam-postgres:/tmp/exam_db.dump
docker exec exam-postgres pg_restore -U postgres -d exam_db --clean --if-exists --no-owner /tmp/exam_db.dump
```

These backups sit on the same machine as the database, so they do not survive losing that machine. Real
production needs either a managed PostgreSQL service or off-server copies with WAL archiving
(point-in-time recovery). Which one is a hosting decision that still has to be made.

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
