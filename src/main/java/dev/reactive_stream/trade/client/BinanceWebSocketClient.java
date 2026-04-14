package dev.reactive_stream.trade.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reactive_stream.trade.domain.TradeLog;
import dev.reactive_stream.trade.dto.BinanceTradeMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    private final ObjectMapper objectMapper;

    @Value("${binance.ws-url}")
    private String wsUrl;

    private final Sinks.Many<TradeLog> sink = Sinks.many()
            .multicast()
            .onBackpressureBuffer(2000, false);


    @PostConstruct
    public void connect() {
        ReactorNettyWebSocketClient client =
                new ReactorNettyWebSocketClient();

        client.execute(
                        URI.create(wsUrl),
                        session -> session
                                .receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .flatMap(this::parse)
                                .doOnNext(trade -> {
                                    recvCounter.incrementAndGet(); // ✅ 추가
                                    log.debug("수신: {} {} {}",
                                            trade.getSymbol(),
                                            trade.getPrice(),
                                            trade.isBuy() ? "매수" : "매도");
                                    Sinks.EmitResult result = sink.tryEmitNext(trade);
                                    if (result.isFailure()) {
                                        log.warn("Sink emit 실패: {} ({})", result, trade.getSymbol());
                                    }
                                })
                                .doOnError(e -> log.error("파싱 오류: {}", e.getMessage()))
                                .then()
                )
                .retryWhen(
                        Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(3))
                                .maxBackoff(Duration.ofSeconds(30))
                                .doBeforeRetry(s ->
                                        log.warn("Binance 재연결 시도 #{}", s.totalRetries()))
                )
                .subscribe();

        // ✅ 추가 — 1초마다 유입 속도 출력
        Flux.interval(Duration.ofSeconds(1))
                .subscribe(tick -> {
                    long count = recvCounter.getAndSet(0);
                    log.info("초당 유입: {}건/초", count);
                });

        log.info("Binance WebSocket 연결 시작: {}", wsUrl);
    }

    public Flux<TradeLog> getStream() {
        return sink.asFlux();
    }

    // ✅ 유입 속도 외부에서 조회 가능하게
    public long getRecvRate() {
        return recvCounter.get();
    }

    private Mono<TradeLog> parse(String json) {
        try {
            BinanceTradeMessage msg =
                    objectMapper.readValue(json, BinanceTradeMessage.class);

            if (!"trade".equals(msg.getEventType())) {
                return Mono.empty();
            }

            TradeLog trade = TradeLog.builder()
                    .symbol(msg.getSymbol())
                    .price(Double.parseDouble(msg.getPrice()))
                    .quantity(Double.parseDouble(msg.getQuantity()))
                    .isBuy(!msg.isMaker())
                    .tradeTime(msg.getTradeTime())
                    .tradeId(msg.getTradeId())
                    .receivedAt(System.currentTimeMillis())
                    .build();

            return Mono.just(trade);

        } catch (Exception e) {
            log.warn("파싱 실패: {}", json);
            return Mono.empty();
        }
    }
}