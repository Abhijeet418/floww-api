package com.floww.exchange.service;

import com.floww.exchange.model.dto.CandleResponse;
import com.floww.exchange.model.entity.Candle;
import com.floww.exchange.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CandleService {

    private final CandleRepository candleRepository;

    public List<CandleResponse> getCandles(String ticker, String resolution, Instant from, Instant to) {
        return candleRepository.findCandles(ticker, resolution, from, to).stream()
                .map(this::toResponse).toList();
    }

    private CandleResponse toResponse(Candle c) {
        return CandleResponse.builder()
                .ticker(c.getTicker()).resolution(c.getResolution())
                .bucket(c.getBucket()).open(c.getOpen()).high(c.getHigh())
                .low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build();
    }
}
