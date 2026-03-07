# Floww Exchange API

A high-performance simulated stock exchange engine built with Spring Boot, TimescaleDB, and Redis. It exposes a REST + Server-Sent Events (SSE) API for order management, real-time market data, candle history, and app registration.

**Hosted API:** [api-floww.vercel.app](https://api-floww.vercel.app)

---

## Features

- **Matching Engine** — LMAX Disruptor-backed order book with price-time priority
- **Order Management** — place, cancel, and query limit/market orders
- **Real-time Market Data** — live order book depth and trade stream via SSE
- **OHLCV Candles** — multiple resolutions with TimescaleDB-backed historical data
- **App Registration** — self-service API key provisioning with webhook support
- **Rate Limiting** — per-app order rate limits and global request throttling
- **Market Sessions** — configurable open/close times with circuit breaker support

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3 |
| Matching Engine | LMAX Disruptor |
| Database | TimescaleDB (PostgreSQL 16) |
| Cache | Redis 7 |
| Containerization | Docker / Docker Compose |

---

## Running Locally

### Prerequisites

- [Docker](https://www.docker.com/get-started) and Docker Compose

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/floww-api.git
cd floww-api
```

### 2. Configure environment variables

```bash
cp .env.example .env
```

Open `.env` and set **both** values:

```dotenv
# Strong password for PostgreSQL
POSTGRES_PASSWORD=your_strong_db_password

# Long random hex string — protects all /admin/* endpoints
# Generate one: openssl rand -hex 32
ADMIN_TOKEN=your_strong_random_admin_token
```

### 3. Start all services

```bash
docker compose up --build
```

This starts:
- **floww-api** on `http://localhost:8081`
- **TimescaleDB** on port `5432`
- **Redis** on port `6379`

The database schema is automatically applied from `scripts/init-db.sql` on first run.

### 4. Verify it's running

```bash
curl http://localhost:8081/market-status
```

---

## API Overview

All responses follow the shape:

```json
{
  "success": true,
  "data": { ... }
}
```

### Public Endpoints (no auth required)

| Method | Path | Description |
|---|---|---|
| `GET` | `/market-status` | Current market session status |
| `GET` | `/tickers` | List all active tickers |
| `GET` | `/tickers/{symbol}` | Get a single ticker |
| `GET` | `/tickers/{symbol}/orderbook?depth=20` | Order book snapshot |
| `GET` | `/tickers/{symbol}/trades?limit=50` | Recent trades |
| `GET` | `/tickers/{symbol}/candles?resolution=1m&from=…&to=…` | OHLCV candles |
| `GET` | `/market-data/{symbol}` | SSE stream — live order book & trades |

#### Candle resolutions

`1m`, `5m`, `15m`, `30m`, `1h`, `4h`, `1d`, `1w`, `1M`

---

### App Registration

Register to receive an API key for order trading.

#### Register

```http
POST /api/apps/register
Content-Type: application/json

{
  "name": "My Trading Bot",
  "contactEmail": "you@example.com",
  "description": "Optional description",
  "webhookUrl": "https://yourapp.com/webhook"
}
```

Returns an API key once your application is approved.

#### Check Status

```http
POST /api/apps/status
Content-Type: application/json

{ "apiKey": "flw_..." }
```

---

### Authenticated Endpoints (require `X-API-KEY` header)

```http
X-API-KEY: flw_your_api_key_here
```

| Method | Path | Description |
|---|---|---|
| `POST` | `/orders` | Place an order |
| `GET` | `/orders/{orderId}` | Get order status |
| `DELETE` | `/orders/{orderId}` | Cancel an order |

#### Place Order — Request Body

```json
{
  "symbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 10,
  "price": 15000
}
```

> Prices are in minor units (e.g. paise / cents). `type` can be `LIMIT` or `MARKET`.

---

### Admin Endpoints (require `X-Admin-Token` header)

```http
X-Admin-Token: your_admin_token
```

| Method | Path | Description |
|---|---|---|
| `POST` | `/admin/tickers` | Create a new ticker |
| `GET` | `/admin/tickers` | List all tickers (including halted) |
| `PATCH` | `/admin/tickers/{symbol}/status` | Halt or reactivate a ticker |
| `GET` | `/admin/apps` | List registered apps |
| `PATCH` | `/admin/apps/{appId}/approve` | Approve an app |
| `PATCH` | `/admin/apps/{appId}/suspend` | Suspend an app |

---

## Configuration

All runtime configuration is in `src/main/resources/application.yml`. The key environment variables are:

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL for PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `REDIS_HOST` | Redis hostname |
| `REDIS_PORT` | Redis port (default `6379`) |
| `ADMIN_TOKEN` | Token for admin endpoints |

Market hours, rate limits, and circuit-breaker thresholds are all tunable in `application.yml`.

---

## Project Structure

```
src/main/java/com/floww/exchange/
├── config/          # Spring config (Redis, CORS, properties)
├── controller/      # REST controllers
├── engine/          # Matching engine & Disruptor pipeline
├── exception/       # Exception types & global handler
├── filter/          # API key & admin token auth filters
├── model/           # Entities, DTOs, enums, events
├── repository/      # JPA & custom bulk repositories
├── seed/            # Historical candle data seeder
└── service/         # Business logic
```

---
