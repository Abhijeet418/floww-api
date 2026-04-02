package com.floww.exchange.seed;

import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.enums.TickerStatus;
import com.floww.exchange.repository.TickerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Generates realistic MACRO OHLCV candle data using Geometric Brownian Motion.
 * * Shifted from 1-minute base to 1-Day base for 5-Year historical generation.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class GbmCandleGenerator {

    private static final int YEARS = 5;
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int TICKS_PER_DAY = 100; // Simulated intra-day movements for H/L
    private static final double ANNUAL_DRIFT = 0.08;     // 8% avg annual return
    private static final double ANNUAL_VOL = 0.30;       // 30% annualized volatility
    private static final double DT = 1.0 / (TRADING_DAYS_PER_YEAR * TICKS_PER_DAY);

    private final TickerRepository tickerRepository;

    public void generateAndWrite(CandleBulkWriter writer) {
        List<Ticker> tickers = tickerRepository.findByStatus(TickerStatus.ACTIVE);
        if (tickers.isEmpty()) {
            log.warn("No active tickers found. Create tickers first, then run the seeder.");
            return;
        }

        log.info("Generating {} years of candles (1d + 1w) for {} tickers...", YEARS, tickers.size());

        for (Ticker ticker : tickers) {
            long startTime = System.currentTimeMillis();

            double price = ticker.getSessionOpenPrice() != null && ticker.getSessionOpenPrice() > 0
                    ? ticker.getSessionOpenPrice() / 100.0  // cents → dollars
                    : 100.0;

            Random rng = new Random(ticker.getSymbol().hashCode());
            double tickerDrift = ANNUAL_DRIFT * (0.5 + rng.nextDouble());
            double tickerVol = ANNUAL_VOL * (0.6 + rng.nextDouble() * 0.8);
            double driftTerm = (tickerDrift - 0.5 * tickerVol * tickerVol) * DT;
            double volTerm = tickerVol * Math.sqrt(DT);

            LocalDate startDate = LocalDate.now().minusYears(YEARS);
            LocalDate currentDate = startDate;
            LocalDate endDate = LocalDate.now();

            List<long[]> dailyCandles = new ArrayList<>(1400);

            while (!currentDate.isAfter(endDate)) {
                if (currentDate.getDayOfWeek() == DayOfWeek.SATURDAY || currentDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    currentDate = currentDate.plusDays(1);
                    continue;
                }

                // Standardize bucket times to Midnight UTC
                long epochSec = currentDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();

                double open = price;
                double high = price;
                double low = price;

                // Simulate intra-day volatility to get realistic wicks
                for (int tick = 0; tick < TICKS_PER_DAY; tick++) {
                    double z = ThreadLocalRandom.current().nextGaussian();
                    price = price * Math.exp(driftTerm + volTerm * z);
                    price = Math.max(0.01, price);
                    high = Math.max(high, price);
                    low = Math.min(low, price);
                }

                double close = price;
                // Daily volume is much larger
                long volume = 10000 + ThreadLocalRandom.current().nextLong(500000); 

                dailyCandles.add(new long[]{
                        epochSec,
                        Math.round(open * 100),
                        Math.round(high * 100),
                        Math.round(low * 100),
                        Math.round(close * 100),
                        volume
                });

                currentDate = currentDate.plusDays(1);
            }

            // 1. Write Base Daily Candles
            writer.writeBatch(ticker.getSymbol(), "1d", dailyCandles);

            // 2. Aggregate into Weekly candles
            writeAggregated(writer, ticker.getSymbol(), "1w", dailyCandles, 
                d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("  {} — Done generating all resolutions in {}ms", ticker.getSymbol(), elapsed);
        }
    }

    /**
     * Time-Aware Aggregator.
     * Uses actual calendar boundaries (Start of Month, Start of Year) rather than fixed seconds.
     */
    private void writeAggregated(CandleBulkWriter writer, String symbol, String resolution, 
                                 List<long[]> baseCandles, Function<ZonedDateTime, ZonedDateTime> truncator) {
        
        // TreeMap automatically sorts by epoch time
        Map<Long, long[]> aggregatedMap = new TreeMap<>();

        for (long[] c : baseCandles) {
            ZonedDateTime date = Instant.ofEpochSecond(c[0]).atZone(ZoneOffset.UTC);
            ZonedDateTime bucket = truncator.apply(date);
            long bucketSec = bucket.toEpochSecond();

            aggregatedMap.compute(bucketSec, (k, existing) -> {
                if (existing == null) {
                    // Start new candle: [bucketSec, open, high, low, close, volume]
                    return new long[]{bucketSec, c[1], c[2], c[3], c[4], c[5]};
                } else {
                    // Update existing candle
                    existing[2] = Math.max(existing[2], c[2]); // High
                    existing[3] = Math.min(existing[3], c[3]); // Low
                    existing[4] = c[4];                        // Close (overwrites until end of period)
                    existing[5] += c[5];                       // Add volume
                    return existing;
                }
            });
        }

        writer.writeBatch(symbol, resolution, new ArrayList<>(aggregatedMap.values()));
    }
}