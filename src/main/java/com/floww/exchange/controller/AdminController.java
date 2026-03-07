package com.floww.exchange.controller;

import com.floww.exchange.model.dto.*;
import com.floww.exchange.service.TickerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TickerService tickerService;

    @PostMapping("/tickers")
    public ResponseEntity<ApiResponse<TickerResponse>> createTicker(
            @Valid @RequestBody CreateTickerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tickerService.createTicker(request)));
    }

    @GetMapping("/tickers")
    public ResponseEntity<ApiResponse<List<TickerResponse>>> listAllTickers() {
        return ResponseEntity.ok(ApiResponse.ok(tickerService.getAllTickers()));
    }

    @PatchMapping("/tickers/{symbol}/status")
    public ResponseEntity<ApiResponse<TickerResponse>> updateTickerStatus(
            @PathVariable String symbol,
            @Valid @RequestBody TickerStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                tickerService.updateTickerStatus(symbol, request.getStatus(), request.getReason())));
    }
}
