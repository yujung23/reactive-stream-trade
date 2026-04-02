# Reactive Streams Trade

Binance WebSocket에서 실시간 암호화폐 체결 데이터를 수신하고,
Reactor 백프레셔 전략 3가지를 비교하는 Spring WebFlux 프로젝트입니다.

---

## 전체 흐름

```
Binance WebSocket
  └→ BinanceWebSocketClient  (Sinks.Many 멀티캐스트)
       └→ TradeStreamService  (백프레셔 전략 선택)
            └→ TradeController (SSE / 드롭 통계 API)
```
<img width="526" height="708" alt="image" src="https://github.com/user-attachments/assets/9dd582d9-34d3-426b-9432-1f21e14c3ccf" />

---

## 각 클래스 설명

### BinanceWebSocketClient

Binance WebSocket에 연결해 실시간 체결 데이터를 수신하는 핵심 컴포넌트입니다.

- `Sinks.many().multicast().directAllOrNothing()` — 다중 구독자 지원. 구독자 중 한 명이라도 demand가 없으면 해당 emit 실패
- `@PostConstruct connect()` — 앱 시작 시 자동으로 WebSocket 연결
- `retryWhen(Retry.backoff(...))` — 연결 끊김 시 3초~최대 30초 간격으로 무한 재연결
- `parse()` — Binance JSON을 TradeLog로 변환. `"trade"` 이벤트만 처리, 나머지는 무시
- `m` 필드 — `m=true`는 매도(maker), `m=false`는 매수(taker)

### TradeStreamService

백프레셔 전략 3가지를 제공합니다. 구독자가 느릴 때 데이터 넘침을 어떻게 처리할지 선택합니다.

| 전략 | 파라미터 | 동작 | 장단점 |
|------|----------|------|--------|
| **buffer** (기본) | `?strategy=buffer` | 버퍼 N개 유지, 초과 시 오래된 것 DROP | 데이터 보존 / 메모리 증가 위험 |
| **latest** | `?strategy=latest` | 최신 값 1개만 유지, 나머지 DROP | 메모리 안정 / 데이터 유실 |
| **limitRate** | `?strategy=limitRate` | 소비자 처리량 최대 N개로 제한 | 시스템 보호 / 처리량 제한 |

드롭 건수는 `AtomicLong`으로 카운트해 `/api/trades/stats`로 조회 가능합니다.

### TradeController

- `GET /api/trades/stream?strategy=buffer` — SSE로 실시간 체결 스트림 전송
- `GET /api/trades/stats` — 전략별 드롭 발생 횟수 조회

### BinanceTradeMessage / TradeLog

- `BinanceTradeMessage` — Binance API 응답 JSON 매핑 DTO (`"e"`, `"s"`, `"p"` 등 단문자 키 사용)
- `TradeLog` — 내부 도메인 객체 (price/quantity는 String → double 변환 완료)

---

## API

### `GET /api/trades/stream`

```bash
curl -N "http://localhost:8080/api/trades/stream?strategy=buffer"
curl -N "http://localhost:8080/api/trades/stream?strategy=latest"
curl -N "http://localhost:8080/api/trades/stream?strategy=limitRate"
```

**응답 (SSE)**
```
event:trade
data:{"symbol":"BTCUSDT","price":96450.0,"quantity":0.082,"buy":true,"tradeTime":1710000000000,"tradeId":123456}
```

### `GET /api/trades/stats`

```json
{
  "buffer": 12,
  "latest": 305,
  "limitRate": 0
}
```

> `limitRate` 전략은 Sink 레벨에서 드롭이 발생하므로 카운트가 집계되지 않습니다.

---

## 설정

`src/main/resources/application.yaml`

```yaml
binance:
  ws-url: wss://stream.binance.com/ws/btcusdt@trade/ethusdt@trade/xrpusdt@trade

stream:
  buffer-size: 500   # 주의: TradeStreamService에서 현재 50으로 하드코딩됨
  limit-rate: 20

server:
  port: 8080
```

구독 심볼 변경: `ws-url` 경로 수정 (형식: `심볼@trade`)

---

## 핵심 Reactive 개념

```
Binance → sink.tryEmitNext() → Flux (hot stream) → backpressure 전략 → SSE
```

- **Hot Stream**: `Sinks.Many`는 구독 여부와 관계없이 데이터가 계속 흘러옵니다
- **Backpressure**: 소비자(브라우저)가 느리면 데이터가 쌓이므로 3가지 전략으로 처리 방식을 선택합니다
- **SSE**: HTTP 커넥션을 유지한 채 서버 → 클라이언트 방향으로 이벤트를 밀어줍니다

---

## 실행

```bash
./gradlew bootRun
```

CORS: `http://localhost:3000` 허용

---

## 기술 스택

- Java 17
- Spring Boot 4.0.5 / Spring WebFlux
- Project Reactor (`Flux`, `Sinks`)
- Reactor Netty WebSocket Client
- Lombok / Jackson
