package com.floww.exchange.service;

import com.floww.exchange.config.ExchangeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Tracks whether the market session is currently open.
 *
 * Market hours are configured in application.yml:
 *   floww.exchange.market-hours.open-time    (HH:mm, default 09:15)
 *   floww.exchange.market-hours.close-time   (HH:mm, default 15:30)
 *   floww.exchange.market-hours.timezone      (default Asia/Kolkata)
 *
 * When the market is closed:
 * - Orders are rejected with MARKET_CLOSED
 * - SSE subscribers receive a "market-status" event with {open: false, ...}
 * - The /market-status endpoint returns the session schedule
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketSessionService {

    private final ExchangeProperties props;

    private ZoneId zone;
    private LocalTime openTime;
    private LocalTime closeTime;

    @PostConstruct
    public void init() {
        ExchangeProperties.MarketHours mh = props.getMarketHours();
        zone = ZoneId.of(mh.getTimezone());
        openTime = LocalTime.parse(mh.getOpenTime(), DateTimeFormatter.ofPattern("HH:mm"));
        closeTime = LocalTime.parse(mh.getCloseTime(), DateTimeFormatter.ofPattern("HH:mm"));
        log.info("Market hours: {} – {} ({})", openTime, closeTime, zone);
    }

    public boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(openTime) && t.isBefore(closeTime);
    }

    /**
     * Returns a map-like snapshot of market session info
     * for API responses and SSE events.
     */
    public MarketStatus getStatus() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        boolean open = isMarketOpen();

        // Next open/close
        ZonedDateTime nextOpen = getNextOpen(now);
        ZonedDateTime nextClose = open ? now.with(closeTime) : getNextClose(now);

        return new MarketStatus(
                open,
                openTime.toString(),
                closeTime.toString(),
                zone.getId(),
                now.toInstant(),
                open ? null : nextOpen.toInstant(),
                open ? nextClose.toInstant() : null
        );
    }

    private ZonedDateTime getNextOpen(ZonedDateTime now) {
        ZonedDateTime candidate = now.with(openTime);
        // If we're past open time today or it's a weekend, advance
        if (now.toLocalTime().isAfter(openTime) || now.toLocalTime().equals(openTime)) {
            candidate = candidate.plusDays(1);
        }
        // Skip weekends
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private ZonedDateTime getNextClose(ZonedDateTime now) {
        ZonedDateTime candidate = getNextOpen(now).with(closeTime);
        return candidate;
    }

    public record MarketStatus(
            boolean open,
            String openTime,
            String closeTime,
            String timezone,
            java.time.Instant serverTime,
            java.time.Instant nextOpenAt,
            java.time.Instant nextCloseAt
    ) {}
}
