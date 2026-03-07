package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.entity.Candle;
import com.floww.exchange.repository.CandleRepository;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OHLCV candle aggregation — consumes trades from the Disruptor
 * and maintains in-memory candle buffers, flushing to DB periodically.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OhlcvAggregationHandler implements EventHandler<TradeEventHolder> {

    private final CandleRepository candleRepository;

    private final Map<String, MutableCandle> candles = new ConcurrentHashMap<>();

    @Override
    public void onEvent(TradeEventHolder holder, long sequence, boolean endOfBatch) {
        updateCandle(holder, "1d", 86400);
    }

    private void updateCandle(TradeEventHolder trade, String resolution, long bucketSeconds) {
        long bucketEpoch = (trade.tradedAt.getEpochSecond() / bucketSeconds) * bucketSeconds;
        String key = trade.ticker + ":" + resolution + ":" + bucketEpoch;

        candles.compute(key, (k, existing) -> {
            if (existing == null) {
                return new MutableCandle(trade.ticker, resolution,
                        Instant.ofEpochSecond(bucketEpoch), trade.price, trade.qty);
            }
            existing.update(trade.price, trade.qty);
            return existing;
        });
    }

    @Scheduled(fixedRate = 10000)
    public void flush() {
        if (candles.isEmpty()) return;

        var snapshot = Map.copyOf(candles);
        candles.clear();

        for (var entry : snapshot.entrySet()) {
            MutableCandle mc = entry.getValue();
            try {
                Candle candle = Candle.builder()
                        .ticker(mc.ticker).resolution(mc.resolution).bucket(mc.bucket)
                        .open(mc.open).high(mc.high).low(mc.low).close(mc.close).volume(mc.volume)
                        .build();
                candleRepository.save(candle);
            } catch (Exception e) {
                candles.putIfAbsent(entry.getKey(), mc);
            }
        }
        log.debug("Flushed {} candle(s)", snapshot.size());
    }

    private static class MutableCandle {
        String ticker, resolution;
        Instant bucket;
        long open, high, low, close, volume;

        MutableCandle(String ticker, String resolution, Instant bucket, long price, long qty) {
            this.ticker = ticker; this.resolution = resolution; this.bucket = bucket;
            this.open = this.high = this.low = this.close = price;
            this.volume = qty;
        }

        void update(long price, long qty) {
            if (price > high) high = price;
            if (price < low) low = price;
            close = price;
            volume += qty;
        }
    }
}
