package com.floww.exchange.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Activated with:  --spring.profiles.active=seed
 *
 * Generates 5 years of historical OHLCV candle data for every active ticker
 * using Geometric Brownian Motion (GBM), then exits.
 *
 * This is a ONE-TIME data seeding operation.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataSeeder implements ApplicationRunner {

    private final GbmCandleGenerator generator;
    private final CandleBulkWriter writer;

    @Override
    public void run(ApplicationArguments args) {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Floww Historical Data Seeder — 5-Year GBM Backfill ");
        log.info("═══════════════════════════════════════════════════════");

        generator.generateAndWrite(writer);

        log.info("═══════════════════════════════════════════════════════");
        log.info("  Seeding complete. Shutting down.                    ");
        log.info("═══════════════════════════════════════════════════════");

        // Exit after seeding — don't start the normal exchange server
        System.exit(0);
    }
}
