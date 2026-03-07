package com.floww.exchange.model.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class CandleId implements Serializable {
    private String ticker;
    private String resolution;
    private Instant bucket;

    public CandleId() {}
    public CandleId(String ticker, String resolution, Instant bucket) {
        this.ticker = ticker; this.resolution = resolution; this.bucket = bucket;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CandleId c)) return false;
        return Objects.equals(ticker, c.ticker) && Objects.equals(resolution, c.resolution) && Objects.equals(bucket, c.bucket);
    }

    @Override
    public int hashCode() { return Objects.hash(ticker, resolution, bucket); }
}
