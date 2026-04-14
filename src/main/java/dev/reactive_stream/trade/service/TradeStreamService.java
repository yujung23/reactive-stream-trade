package dev.reactive_stream.trade.service;

import dev.reactive_stream.trade.client.BinanceWebSocketClient;
import dev.reactive_stream.trade.domain.TradeLog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;

import java.time.Duration;
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

    // ✅ limitRate 전용 수신 카운터 추가
    private final AtomicLong limitRecv      = new AtomicLong();
    private final AtomicLong limitProc      = new AtomicLong();
    private final AtomicLong limitLatency   = new AtomicLong();
    private final AtomicLong limitLatCount  = new AtomicLong();

    private final AtomicLong totalRecv = new AtomicLong();

    // ✅ 처리 속도 모니터링
    private final AtomicLong procCounter = new AtomicLong();

    @PostConstruct
    public void startMonitor() {
        Flux.interval(Duration.ofSeconds(1))
                .subscribe(tick -> {
                    long proc = procCounter.getAndSet(0);
                    log.info("초당 처리: {}건/초", proc);
                });
    }

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
                    procCounter.incrementAndGet(); // ✅ 처리 속도 카운트
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
                    procCounter.incrementAndGet(); // ✅ 처리 속도 카운트
                    long lat = System.currentTimeMillis() - t.getReceivedAt();
                    latestLatency.addAndGet(lat);
                    latestLatCount.incrementAndGet();
                });
    }

    public Flux<TradeLog> streamWithLimitRate() {
        return wsClient.getStream()
                .doOnNext(t -> limitRecv.incrementAndGet()) // ✅ 수신 전 카운트
                .limitRate(limitRate)
                .doOnNext(t -> {
                    limitProc.incrementAndGet();
                    procCounter.incrementAndGet(); // ✅ 처리 속도 카운트
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

    public Map<String, Object> getMetrics() {
        long blc  = bufferLatCount.get();
        long llc  = latestLatCount.get();
        long lrlc = limitLatCount.get();

        // ✅ limitRate 실질 드롭 = 수신 - 처리
        long limitDrop = Math.max(0, limitRecv.get() - limitProc.get());

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
                        "drop",       limitDrop, // ✅ 실질 드롭
                        "proc",       limitProc.get(),
                        "avgLatency", lrlc > 0 ? limitLatency.get() / lrlc : 0
                ),
                "totalRecv", totalRecv.get()
        );
    }
}