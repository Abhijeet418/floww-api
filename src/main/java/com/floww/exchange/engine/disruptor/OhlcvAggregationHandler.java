package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.entity.Candle;
import com.floww.exchange.repository.CandleRepository;
import com.lmax.disruptor.EventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OHLCV candle aggregation — consumes trades from the Disruptor
 * and maintains in-memory candle buffers, flushing to DB periodically.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OhlcvAggregationHandler implements EventHandler<TradeEventHolder> {

    private final CandleRepository candleRepository;

    // Double-buffer: the Disruptor thread writes to the active map.
    // The flush thread atomically swaps in a fresh map and drains the old one.
    private final AtomicReference<ConcurrentHashMap<String, MutableCandle>> activeCandles =
            new AtomicReference<>(new ConcurrentHashMap<>());

    @Override
    public void onEvent(TradeEventHolder holder, long sequence, boolean endOfBatch) {
        // Skip cancellation-only events (no trade data)
        if (holder.tradeId == null) return;
        // Daily: midnight UTC floor
        long dayBucket = (holder.tradedAt.getEpochSecond() / 86400) * 86400;
        applyTrade(holder, "1d", Instant.ofEpochSecond(dayBucket));

        // Weekly: Monday midnight UTC (aligned with seeder)
        ZonedDateTime monday = holder.tradedAt.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC);
        applyTrade(holder, "1w", monday.toInstant());
    }

    private void applyTrade(TradeEventHolder trade, String resolution, Instant bucket) {
        String key = trade.ticker + ":" + resolution + ":" + bucket.getEpochSecond();

        activeCandles.get().compute(key, (k, existing) -> {
            if (existing == null) {
                return new MutableCandle(trade.ticker, resolution, bucket, trade.price, trade.qty);
            }
            existing.update(trade.price, trade.qty);
            return existing;
        });
    }

    @Scheduled(fixedRate = 10000)
    public void flush() {
        // Atomically swap the active map with a fresh one — zero data loss window.
        // Any trades arriving during flush write to the new map.
        ConcurrentHashMap<String, MutableCandle> snapshot = activeCandles.getAndSet(new ConcurrentHashMap<>());
        if (snapshot.isEmpty()) return;

        for (var entry : snapshot.entrySet()) {
            MutableCandle mc = entry.getValue();
            try {
                // Merge with existing candle (e.g. from seeder) instead of overwriting
                Candle existing = candleRepository
                        .findById(new com.floww.exchange.model.entity.CandleId(mc.ticker, mc.resolution, mc.bucket))
                        .orElse(null);

                if (existing != null) {
                    existing.setHigh(Math.max(existing.getHigh(), mc.high));
                    existing.setLow(Math.min(existing.getLow(), mc.low));
                    existing.setClose(mc.close);
                    existing.setVolume(existing.getVolume() + mc.volume);
                    candleRepository.save(existing);
                } else {
                    candleRepository.save(Candle.builder()
                            .ticker(mc.ticker).resolution(mc.resolution).bucket(mc.bucket)
                            .open(mc.open).high(mc.high).low(mc.low).close(mc.close).volume(mc.volume)
                            .build());
                }
            } catch (Exception e) {
                // Re-insert into the current active map so the next flush retries
                activeCandles.get().putIfAbsent(entry.getKey(), mc);
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
