package dev.reactive_stream.trade.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeLog {
    private String symbol;
    private double price;
    private double quantity;
    private boolean isBuy;
    private long tradeTime;
    private long tradeId;
    private long receivedAt; // ✅ 추가
}