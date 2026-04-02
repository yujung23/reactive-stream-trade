# Reactive Streams Trade

Binance WebSocket에서 실시간 암호화폐 체결 데이터를 수신하고,  
Reactor 기반 백프레셔 전략 3가지를 비교하는 Spring WebFlux 프로젝트입니다.

---

## 목차

- [프로젝트 개요](#1-프로젝트-개요)
- [아키텍처](#2-아키텍처)
- [전체 흐름](#전체-흐름)
- [백프레셔 전략 비교](#4-백프레셔-전략-비교)
- [백프레셔 동작 예시](#5-백프레셔-동작-예시)
- [주요 컴포넌트](#6-주요-컴포넌트)
  - [BinanceWebSocketClient](#binancewebsocketclient)
  - [TradeStreamService](#tradestreamservice)
  - [TradeController](#tradecontroller)
  - [BinanceTradeMessage / TradeLog](#binancetrademessage--tradelog)
- [API](#api)
- [설정](#설정)
- [핵심 Reactive 개념](#핵심-reactive-개념)
- [실행](#실행)
- [기술 스택](#기술-스택)

  ---
  
## 1. 프로젝트 개요

- WebSocket 기반 실시간 스트리밍 데이터 처리
- Reactor `Flux`와 `Sinks`를 활용한 Hot Stream 구성
- Backpressure 전략(buffer / latest / limitRate) 비교
- SSE(Server-Sent Events)를 통한 실시간 데이터 전달
- 드롭(drop) 발생 통계 수집 및 모니터링

---

## 2. 아키텍처

```
Binance WebSocket
  └→ BinanceWebSocketClient  (Sinks.Many 멀티캐스트)
       └→ TradeStreamService  (백프레셔 전략 선택)
            └→ TradeController (SSE / 드롭 통계 API)
```
<img width="526" height="708" alt="image" src="https://github.com/user-attachments/assets/9dd582d9-34d3-426b-9432-1f21e14c3ccf" />

---

## 전체 흐름

```
Binance → WebSocket → Sink.emit()
→ Flux (Hot Stream)
→ Backpressure Strategy
→ SSE (Browser)
```

---

## 4. 백프레셔 전략 비교

| 전략 | 파라미터 | 동작 | 처리 방식 | 특징 |
|------|----------|------|-----------|------|
| buffer | `?strategy=buffer` | 버퍼 N개 유지 | 오래된 데이터 DROP | 데이터 보존 |
| latest | `?strategy=latest` | 최신 값 1개 유지 | 이전 데이터 DROP | 최신성 유지 |
| limitRate | `?strategy=limitRate` | 처리량 제한 | request 수 제한 | 시스템 보호 |

buffer와 latest는 **데이터가 넘쳤을 때 처리하는 전략**,  
limitRate는 **요청량(request)을 제어해 백프레셔를 구현하는 방식**입니다.

---

## 5. 백프레셔 동작 예시

가정:

```
Incoming: 1000 events/s
Consumer: 20 events/s
Buffer size: 50
```
| 전략 | Queue | Dropped | Delay |
|------|------|--------|------|
| buffer | 증가 | 증가 | 증가 |
| latest | 1 | 매우 많음 | 낮음 |
| limitRate | 0 | 없음 | 일정 |

---

## 6. 주요 컴포넌트

### BinanceWebSocketClient

Binance WebSocket에 연결해 실시간 체결 데이터를 수신하는 핵심 컴포넌트입니다.

- `Sinks.many().multicast().directAllOrNothing()`
  - 다중 구독자 지원
  - 구독자 중 한 명이라도 demand가 없으면 emit 실패
- `@PostConstruct connect()`
  - 애플리케이션 시작 시 자동 WebSocket 연결
- `retryWhen(Retry.backoff(...))`
  - 연결 끊김 시 자동 재연결
- `parse()`
  - Binance JSON → TradeLog 변환
- `m` 필드
  - `true` = 매도(maker)
  - `false` = 매수(taker)

---

### TradeStreamService

백프레셔 전략을 선택하고 Flux 스트림을 구성하는 서비스입니다.

역할:

- Backpressure 전략 적용
- Drop 이벤트 카운트
- 스트림 생성 및 전달

사용 전략:

- buffer
- latest
- limitRate

Drop 카운트:
AtomicLong

---

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
  buffer-size: 300   # 전략별 비교를 위해 100으로 조절
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
