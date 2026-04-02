package com.floww.exchange.controller;

import com.floww.exchange.model.dto.*;
import com.floww.exchange.service.CandleService;
import com.floww.exchange.service.MarketDataService;
import com.floww.exchange.service.MarketSessionService;
import com.floww.exchange.service.SnapshotService;
import com.floww.exchange.service.TickerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class PublicController {

    private final TickerService tickerService;
    private final SnapshotService snapshotService;
    private final CandleService candleService;
    private final MarketDataService marketDataService;
    private final MarketSessionService marketSessionService;

    /* ---- market status ---- */

    @GetMapping("/market-status")
    public ResponseEntity<ApiResponse<MarketSessionService.MarketStatus>> marketStatus() {
        return ResponseEntity.ok(ApiResponse.ok(marketSessionService.getStatus()));
    }

    /* ---- tickers ---- */

    @GetMapping("/tickers")
    public ResponseEntity<ApiResponse<List<TickerResponse>>> listTickers() {
        return ResponseEntity.ok(ApiResponse.ok(tickerService.getActiveTickers()));
    }

    @GetMapping("/tickers/{symbol}")
    public ResponseEntity<ApiResponse<TickerResponse>> getTicker(@PathVariable String symbol) {
        return ResponseEntity.ok(ApiResponse.ok(tickerService.getBySymbol(symbol)));
    }

    /* ---- order book ---- */

    @GetMapping("/tickers/{symbol}/orderbook")
    public ResponseEntity<ApiResponse<OrderBookSnapshot>> getOrderBook(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int depth) {
        return ResponseEntity.ok(ApiResponse.ok(snapshotService.getSnapshot(symbol)));
    }

    /* ---- trades ---- */

    @GetMapping("/tickers/{symbol}/trades")
    public ResponseEntity<ApiResponse<List<TradeResponse>>> getRecentTrades(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(tickerService.getRecentTrades(symbol, Math.min(limit, 500))));
    }

    /* ---- candles ---- */

    @GetMapping("/tickers/{symbol}/candles")
    public ResponseEntity<ApiResponse<List<CandleResponse>>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String resolution,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (to == null) to = Instant.now();
        if (from == null) from = to.minusSeconds(90L * 86400); // default 90 days
        return ResponseEntity.ok(ApiResponse.ok(candleService.getCandles(symbol, resolution, from, to)));
    }

    /* ---- SSE market data ---- */

    @GetMapping(value = "/market-data/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMarketData(
            @PathVariable String symbol,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            jakarta.servlet.http.HttpServletRequest httpReq) {
        String clientKey = marketDataService.resolveClientKey(apiKey, httpReq.getRemoteAddr());
        return marketDataService.subscribe(symbol, lastEventId, clientKey);
    }
}
