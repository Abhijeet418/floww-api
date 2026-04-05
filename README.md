# Floww Exchange API

Simulated stock exchange backend with a matching engine, real-time market data, and REST API. Built with Java 21, Spring Boot 3, TimescaleDB, and Redis.

## What it does

- Matching engine backed by LMAX Disruptor with price-time priority
- Limit and market orders with cancel support
- Live order book and trade stream over SSE
- OHLCV candle aggregation at multiple resolutions (1m to 1M)
- API key provisioning and per-app rate limiting
- Configurable market sessions with circuit breakers

## Tech

- **Java 21 / Spring Boot 3**
- **LMAX Disruptor** for low-latency order matching
- **TimescaleDB** (PostgreSQL 16) for time-series candle data
- **Redis 7** for caching and rate limiting
- **Docker Compose** for local setup

## Getting started

Requires [Docker](https://www.docker.com/get-started).

```bash
git clone https://github.com/your-username/floww-api.git
cd floww-api
cp .env.example .env
```

Edit `.env` with your values:

```dotenv
POSTGRES_PASSWORD=your_db_password
ADMIN_TOKEN=your_admin_token   # openssl rand -hex 32
```

```bash
docker compose up --build
```

Services start on:
- API: `http://localhost:8081`
- TimescaleDB: port `5432`
- Redis: port `6379`

Schema is applied automatically from `scripts/init-db.sql` on first run.

```bash
curl http://localhost:8081/market-status
```

## API

All responses use this format:

```json
{ "success": true, "data": { ... } }
```

### Public (no auth)

| Method | Path | Description |
|---|---|---|
| GET | `/market-status` | Market session status |
| GET | `/tickers` | All active tickers |
| GET | `/tickers/{symbol}` | Single ticker |
| GET | `/tickers/{symbol}/orderbook?depth=20` | Order book snapshot |
| GET | `/tickers/{symbol}/trades?limit=50` | Recent trades |
| GET | `/tickers/{symbol}/candles?resolution=1m&from=...&to=...` | OHLCV candles |
| GET | `/market-data/{symbol}` | SSE stream (order book + trades) |

Candle resolutions: `1m` `5m` `15m` `30m` `1h` `4h` `1d` `1w` `1M`

### App registration

```http
POST /api/apps/register
Content-Type: application/json

{
  "name": "My Trading Bot",
  "contactEmail": "you@example.com",
  "description": "Optional",
  "webhookUrl": "https://yourapp.com/webhook"
}
```

Check status:

```http
POST /api/apps/status
Content-Type: application/json

{ "apiKey": "flw_..." }
```

### Authenticated (requires `X-API-KEY` header)

| Method | Path | Description |
|---|---|---|
| POST | `/orders` | Place order |
| GET | `/orders/{orderId}` | Order status |
| DELETE | `/orders/{orderId}` | Cancel order |

Example order body:

```json
{
  "symbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 10,
  "price": 15000
}
```

Prices are in minor units (paise/cents). Type can be `LIMIT` or `MARKET`.

### Admin (requires `X-Admin-Token` header)

| Method | Path | Description |
|---|---|---|
| POST | `/admin/tickers` | Create ticker |
| GET | `/admin/tickers` | List all tickers |
| PATCH | `/admin/tickers/{symbol}/status` | Halt/reactivate ticker |
| GET | `/admin/apps` | List registered apps |
| PATCH | `/admin/apps/{appId}/approve` | Approve app |
| PATCH | `/admin/apps/{appId}/suspend` | Suspend app |

## Configuration

Runtime config lives in `src/main/resources/application.yml`.

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL |
| `SPRING_DATASOURCE_PASSWORD` | DB password |
| `REDIS_HOST` / `REDIS_PORT` | Redis connection |
| `ADMIN_TOKEN` | Admin endpoint auth |

Market hours, rate limits, and circuit breaker thresholds are configurable in `application.yml`.

## Project structure

```
src/main/java/com/floww/exchange/
├── config/          Spring config (Redis, CORS, properties)
├── controller/      REST controllers
├── engine/          Matching engine + Disruptor pipeline
├── exception/       Exception types and global handler
├── filter/          Auth filters (API key, admin token)
├── model/           Entities, DTOs, enums, events
├── repository/      JPA and bulk repositories
├── seed/            Historical candle data seeder
└── service/         Business logic
```
