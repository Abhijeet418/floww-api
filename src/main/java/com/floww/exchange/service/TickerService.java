package com.floww.exchange.service;

import com.floww.exchange.config.ExchangeProperties;
import com.floww.exchange.model.dto.CreateTickerRequest;
import com.floww.exchange.model.dto.TickerResponse;
import com.floww.exchange.model.dto.TradeResponse;
import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.entity.Trade;
import com.floww.exchange.model.enums.TickerStatus;
import com.floww.exchange.repository.TickerRepository;
import com.floww.exchange.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickerService {

    private final TickerRepository tickerRepository;
    private final TradeRepository tradeRepository;
    private final ExchangeProperties exchangeProperties;
    private final TickerCache tickerCache;

    private final Set<String> activeTickers = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        tickerRepository.findByStatus(TickerStatus.ACTIVE).forEach(t -> activeTickers.add(t.getSymbol()));
        log.info("Initialized {} active tickers", activeTickers.size());
    }

    @Transactional
    public TickerResponse createTicker(CreateTickerRequest request) {
        if (tickerRepository.existsBySymbol(request.getSymbol().toUpperCase()))
            throw new IllegalArgumentException("Ticker already exists: " + request.getSymbol());

        Ticker ticker = Ticker.builder()
                .symbol(request.getSymbol().toUpperCase())
                .name(request.getName())
                .lotSize(request.getLotSize())
                .sessionOpenPrice(request.getInitialPrice())
                .status(TickerStatus.ACTIVE)
                .build();
        tickerRepository.save(ticker);
        activeTickers.add(ticker.getSymbol());

        log.info("Ticker created: {}", ticker.getSymbol());
        return toResponse(ticker);
    }

    public List<TickerResponse> getAllTickers() {
        return tickerRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public TickerResponse updateTickerStatus(String symbol, TickerStatus status, String reason) {
        Ticker ticker = tickerRepository.findBySymbol(symbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Ticker not found: " + symbol));

        ticker.setStatus(status);
        if (status == TickerStatus.HALTED) {
            ticker.setHaltReason(reason);
            ticker.setHaltedUntil(Instant.now().plusSeconds(exchangeProperties.getCircuitBreaker().getHaltDurationMinutes() * 60L));
            activeTickers.remove(ticker.getSymbol());
        } else {
            ticker.setHaltReason(null);
            ticker.setHaltedUntil(null);
            activeTickers.add(ticker.getSymbol());
        }
        tickerRepository.save(ticker);
        return toResponse(ticker);
    }

    /**
     * Runs at market open (09:15 IST, Mon–Fri).
     * Snapshots each active ticker's current LTP into sessionOpenPrice so that
     * price-deviation checks use today's actual open as the reference price.
     * Tickers with no trades yet keep their existing sessionOpenPrice (initial seed).
     */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void snapshotSessionOpenPrices() {
        List<Ticker> active = tickerRepository.findByStatus(TickerStatus.ACTIVE);
        int updated = 0;
        for (Ticker ticker : active) {
            Long ltp = tickerCache.getRefPrice(ticker.getSymbol());
            if (ltp != null && ltp > 0 && !ltp.equals(ticker.getSessionOpenPrice())) {
                ticker.setSessionOpenPrice(ltp);
                tickerRepository.save(ticker);
                updated++;
            }
        }
        log.info("Session open prices snapshotted for {}/{} active tickers", updated, active.size());
    }

    /**
     * Circuit breaker: auto-reactivate halted tickers whose halt period has expired.
     */
    @Scheduled(fixedRate = 30000)
    public void checkHaltExpiry() {
        tickerRepository.findByStatus(TickerStatus.HALTED).forEach(t -> {
            if (t.getHaltedUntil() != null && t.getHaltedUntil().isBefore(Instant.now())) {
                t.setStatus(TickerStatus.ACTIVE);
                t.setHaltReason(null);
                t.setHaltedUntil(null);
                tickerRepository.save(t);
                activeTickers.add(t.getSymbol());
                log.info("Ticker {} auto-reactivated after halt expiry", t.getSymbol());
            }
        });
    }

    public List<TickerResponse> getActiveTickers() {
        return tickerRepository.findByStatus(TickerStatus.ACTIVE).stream().map(this::toResponse).toList();
    }

    public TickerResponse getBySymbol(String symbol) {
        Ticker ticker = tickerRepository.findBySymbol(symbol.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Ticker not found: " + symbol));
        return toResponse(ticker);
    }

    public List<TradeResponse> getRecentTrades(String symbol, int limit) {
        return tradeRepository.findByTickerOrderByTradedAtDesc(symbol.toUpperCase(),
                PageRequest.of(0, limit)).stream()
                .map(this::toTradeResponse)
                .toList();
    }

    public boolean isActive(String symbol) {
        return activeTickers.contains(symbol);
    }

    private TickerResponse toResponse(Ticker t) {
        return TickerResponse.builder()
                .symbol(t.getSymbol()).name(t.getName())
                .status(t.getStatus().name()).lotSize(t.getLotSize())
                .sessionOpenPrice(t.getSessionOpenPrice())
                .exchangeType(exchangeProperties.getType())
                .build();
    }

    private TradeResponse toTradeResponse(Trade t) {
        return TradeResponse.builder()
                .tradeId(t.getTradeId())
                .ticker(t.getTicker())
                .price(t.getPrice())
                .qty(t.getQty())
                .buyOrderId(t.getBuyOrderId())
                .sellOrderId(t.getSellOrderId())
                .tradedAt(t.getTradedAt())
                .build();
    }
}
