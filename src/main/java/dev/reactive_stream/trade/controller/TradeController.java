package dev.reactive_stream.trade.controller;

import dev.reactive_stream.trade.domain.TradeLog;
import dev.reactive_stream.trade.service.TradeStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

    private final TradeStreamService tradeStreamService;

    // SSE 스트림 엔드포인트
    @GetMapping(
            value = "/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<TradeLog>> stream(
            @RequestParam(defaultValue = "buffer") String strategy
    ) {
        return tradeStreamService.getStream(strategy)
                .map(trade -> ServerSentEvent.<TradeLog>builder()
                        .event("trade")
                        .data(trade)
                        .build());
    }


    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return tradeStreamService.getMetrics();
    }
}
