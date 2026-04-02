package dev.reactive_stream.trade.service;


import dev.reactive_stream.trade.client.BinanceWebSocketClient;
import dev.reactive_stream.trade.domain.TradeLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy; // ✅ 이걸로 교체
import reactor.core.publisher.Flux;
// import reactor.core.publisher.FluxSink; ← 이거 삭제

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeStreamService {

    private final BinanceWebSocketClient wsClient;

    @Value("${stream.buffer-size:500}")
    private int bufferSize;

    @Value("${stream.limit-rate:20}")
    private int limitRate;

    // 전략별 독립 카운터
    private final AtomicLong bufferDrop     = new AtomicLong();
    private final AtomicLong bufferProc     = new AtomicLong();
    private final AtomicLong bufferLatency  = new AtomicLong();
    private final AtomicLong bufferLatCount = new AtomicLong();

    private final AtomicLong latestDrop     = new AtomicLong();
    private final AtomicLong latestProc     = new AtomicLong();
    private final AtomicLong latestLatency  = new AtomicLong();
    private final AtomicLong latestLatCount = new AtomicLong();

    private final AtomicLong limitProc      = new AtomicLong();
    private final AtomicLong limitLatency   = new AtomicLong();
    private final AtomicLong limitLatCount  = new AtomicLong();

    private final AtomicLong totalRecv = new AtomicLong();

    public Flux<TradeLog> streamWithBuffer() {
        return wsClient.getStream()
                .doOnNext(t -> totalRecv.incrementAndGet())
                .onBackpressureBuffer(
                        bufferSize,
                        dropped -> bufferDrop.incrementAndGet(),
                        BufferOverflowStrategy.DROP_OLDEST
                )
                .doOnNext(t -> {
                    bufferProc.incrementAndGet();
                    long lat = System.currentTimeMillis() - t.getReceivedAt();
                    bufferLatency.addAndGet(lat);
                    bufferLatCount.incrementAndGet();
                });
    }

    public Flux<TradeLog> streamWithLatest() {
        return wsClient.getStream()
                .doOnNext(t -> totalRecv.incrementAndGet())
                .onBackpressureLatest()
                .doOnDiscard(TradeLog.class, d -> latestDrop.incrementAndGet())
                .doOnNext(t -> {
                    latestProc.incrementAndGet();
                    long lat = System.currentTimeMillis() - t.getReceivedAt();
                    latestLatency.addAndGet(lat);
                    latestLatCount.incrementAndGet();
                });
    }

    public Flux<TradeLog> streamWithLimitRate() {
        return wsClient.getStream()
                .limitRate(limitRate)
                .doOnNext(t -> {
                    limitProc.incrementAndGet();
                    long lat = System.currentTimeMillis() - t.getReceivedAt();
                    limitLatency.addAndGet(lat);
                    limitLatCount.incrementAndGet();
                });
    }

    public Flux<TradeLog> getStream(String strategy) {
        return switch (strategy) {
            case "latest"    -> streamWithLatest();
            case "limitRate" -> streamWithLimitRate();
            default          -> streamWithBuffer();
        };
    }

    // 지표 반환
    public Map<String, Object> getMetrics() {
        long blc = bufferLatCount.get();
        long llc = latestLatCount.get();
        long lrlc = limitLatCount.get();

        return Map.of(
                "buffer", Map.of(
                        "drop",       bufferDrop.get(),
                        "proc",       bufferProc.get(),
                        "avgLatency", blc > 0 ? bufferLatency.get() / blc : 0
                ),
                "latest", Map.of(
                        "drop",       latestDrop.get(),
                        "proc",       latestProc.get(),
                        "avgLatency", llc > 0 ? latestLatency.get() / llc : 0
                ),
                "limitRate", Map.of(
                        "drop",       0,
                        "proc",       limitProc.get(),
                        "avgLatency", lrlc > 0 ? limitLatency.get() / lrlc : 0
                ),
                "totalRecv", totalRecv.get()
        );
    }
}