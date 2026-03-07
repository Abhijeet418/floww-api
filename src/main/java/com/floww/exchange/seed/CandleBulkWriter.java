package com.floww.exchange.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.List;

/**
 * Bulk inserts candle data directly via JDBC for maximum throughput.
 *
 * Uses batched INSERT with ON CONFLICT DO NOTHING (idempotent re-runs).
 * Processes 10,000 rows per batch to balance memory vs. round-trips.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class CandleBulkWriter {

    private static final int BATCH_SIZE = 2_000;
    private static final String INSERT_SQL =
            "INSERT INTO candle (ticker, resolution, bucket, open, high, low, close, volume) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (ticker, resolution, bucket) DO NOTHING";

    private final DataSource dataSource;

    /**
     * Write a batch of candles for one ticker + resolution.
     * @param ticker     e.g., "AAPL"
     * @param resolution e.g., "1m", "5m", "1h", "1d"
     * @param candles    list of [epochSec, open, high, low, close, volume]
     */
    public void writeBatch(String ticker, String resolution, List<long[]> candles) {
        if (candles.isEmpty()) return;

        long inserted = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                int count = 0;
                for (long[] c : candles) {
                    ps.setString(1, ticker);
                    ps.setString(2, resolution);
                    ps.setTimestamp(3, Timestamp.from(Instant.ofEpochSecond(c[0])));
                    ps.setLong(4, c[1]);  // open
                    ps.setLong(5, c[2]);  // high
                    ps.setLong(6, c[3]);  // low
                    ps.setLong(7, c[4]);  // close
                    ps.setLong(8, c[5]);  // volume
                    ps.addBatch();
                    count++;

                    if (count % BATCH_SIZE == 0) {
                        int[] results = ps.executeBatch();
                        conn.commit();
                        inserted += countInserted(results);
                    }
                }

                // Flush remaining
                if (count % BATCH_SIZE != 0) {
                    int[] results = ps.executeBatch();
                    conn.commit();
                    inserted += countInserted(results);
                }
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to write candles for {} {}: {}", ticker, resolution, e.getMessage());
        }

        log.info("    {} {} — inserted {} rows", ticker, resolution, inserted);
    }

    private long countInserted(int[] results) {
        long total = 0;
        for (int r : results) {
            if (r >= 0) total += r;
            else if (r == Statement.SUCCESS_NO_INFO) total++;
        }
        return total;
    }
}
