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
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWebSocketClient {

    private final ObjectMapper objectMapper;

    @Value("${binance.ws-url}")
    private String wsUrl;

    // Flux.create() 대신 Sinks 사용 — 다중 구독자 지원
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
                                    log.debug("수신: {} {} {}",
                                            trade.getSymbol(),
                                            trade.getPrice(),
                                            trade.isBuy() ? "매수" : "매도");
                                    sink.tryEmitNext(trade);
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

        log.info("Binance WebSocket 연결 시작: {}", wsUrl);
    }

    public Flux<TradeLog> getStream() {
        return sink.asFlux();
    }

    private reactor.core.publisher.Mono<TradeLog> parse(String json) {
        try {
            BinanceTradeMessage msg =
                    objectMapper.readValue(json, BinanceTradeMessage.class);

            // "trade" 이벤트만 처리
            if (!"trade".equals(msg.getEventType())) {
                return reactor.core.publisher.Mono.empty();
            }

            TradeLog trade = TradeLog.builder()
                    .symbol(msg.getSymbol())
                    .price(Double.parseDouble(msg.getPrice()))
                    .quantity(Double.parseDouble(msg.getQuantity()))
                    .isBuy(!msg.isMaker()) // maker=true → 매도
                    .tradeTime(msg.getTradeTime())
                    .tradeId(msg.getTradeId())
                    .receivedAt(System.currentTimeMillis())
                    .build();

            return reactor.core.publisher.Mono.just(trade);

        } catch (Exception e) {
            log.warn("파싱 실패: {}", json);
            return reactor.core.publisher.Mono.empty();
        }
    }
}