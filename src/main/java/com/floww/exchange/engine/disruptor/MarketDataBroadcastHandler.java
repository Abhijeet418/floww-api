package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.dto.MarketDataEvent;
import com.floww.exchange.service.MarketDataService;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Market data broadcast — consumes trades from the Disruptor and pushes
 * real-time price updates via SSE to connected clients.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataBroadcastHandler implements EventHandler<TradeEventHolder> {

    private final MarketDataService marketDataService;

    @Override
    public void onEvent(TradeEventHolder holder, long sequence, boolean endOfBatch) {
        try {
            MarketDataEvent event = MarketDataEvent.builder()
                    .type("TRADE")
                    .ticker(holder.ticker)
                    .price(holder.price)
                    .qty(holder.qty)
                    .ltp(holder.lastTradedPrice)
                    .volume(holder.sessionVolume)
                    .bestBid(holder.bestBid)
                    .bestAsk(holder.bestAsk)
                    .build();

            marketDataService.broadcast(holder.ticker, event);
        } catch (Exception e) {
            log.warn("Failed to broadcast market data for {}: {}", holder.ticker, e.getMessage());
        }
    }
}
