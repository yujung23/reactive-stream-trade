 Binance 실시간 체결 데이터를 Reactive Streams로 처리하는 Spring WebFlux 애플리케이션입니다.

  ---
  전체 데이터 흐름

  Binance WS ──→ BinanceWebSocketClient ──→ TradeStreamService ──→ TradeController ──→ SSE(브라우저)
                 (Sinks.Many)               (Backpressure 전략)      (ServerSentEvent)


  ---
  각 클래스 설명

  BinanceWebSocketClient (client 레이어)

  Binance WebSocket에 연결해 실시간 체결 데이터를 수신하는 핵심 컴포넌트입니다.

  - Sinks.Many<TradeLog> — 멀티캐스트 싱크. 여러 구독자가 동시에 스트림을 받을 수 있고, 버퍼 2000개를 유지합니다.
  - @PostConstruct connect() — 앱 시작 시 자동으로 WebSocket 연결을 맺습니다.
  - retryWhen(Retry.backoff(...)) — 연결 끊김 시 3초~최대 30초 간격으로 무한 재연결합니다.
  - parse() — Binance JSON ({"e":"trade","s":"BTCUSDT","p":"...","m":false,...})을 TradeLog로 변환. "trade" 이벤트만 처리하고 나머지는 무시합니다.
  - maker 필드 — Binance에서 m=true는 매도(maker), m=false는 매수(taker)를 의미합니다.

  TradeStreamService (service 레이어)

  Backpressure 전략 3가지를 제공합니다. 구독자가 느릴 때 데이터 넘침을 어떻게 처리할지 선택합니다.

  ┌───────────────┬─────────────────────┬─────────────────────────────────────────────┐
  │     전략       │      파라미터          │                    동작                     │
  ├───────────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ buffer (기본)  │ ?strategy=buffer    │ 최대 50개 버퍼, 넘치면 가장 오래된 것 DROP         │
  ├───────────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ latest        │ ?strategy=latest    │ 최신 1개만 유지, 나머지 DROP                     │
  ├───────────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ limitRate     │ ?strategy=limitRate │ 초당 20개로 속도 제한                            │
  └───────────────┴─────────────────────┴─────────────────────────────────────────────┘

  드롭된 건수는 AtomicLong으로 카운트해 조회 가능합니다.

  | 전략 | 장점 | 단점 | 핵심 키워드 |
|------|------|------|-------------|
| Buffer | 데이터 보존 | 메모리 증가 위험 | 데이터 안정성 |
| Latest | 메모리 안정 | 데이터 유실 | 최신 상태 유지 |
| LimitRate | 시스템 보호 | 처리량 제한 | 속도 제어 |

  TradeController (API 레이어)

  두 개의 엔드포인트를 제공합니다.

  - GET /api/trades/stream?strategy=buffer — SSE(Server-Sent Events)로 실시간 체결 스트림 전송
  - GET /api/trades/stats — 각 전략별 DROP 발생 횟수 조회

  BinanceTradeMessage / TradeLog

  - BinanceTradeMessage — Binance API 응답 JSON 그대로 매핑하는 DTO ("e", "s", "p" 등 단문자 키)
  - TradeLog — 내부에서 사용하는 도메인 객체 (price/quantity는 String → double 변환 완료)

  ---
  핵심 Reactive 개념

  Binance → sink.tryEmitNext() → Flux(hot stream) → backpressure 전략 → SSE

  - Hot Stream: Sinks.Many는 구독 여부와 관계없이 데이터가 계속 흘러옵니다.
  - Backpressure: 소비자(브라우저)가 느리면 데이터가 쌓이므로, 3가지 전략으로 처리 방식을 선택합니다.
  - SSE: HTTP 커넥션을 유지한 채 서버→클라이언트 방향으로 이벤트를 밀어줍니다.
