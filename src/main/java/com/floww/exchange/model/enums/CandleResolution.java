package com.floww.exchange.model.enums;

public enum CandleResolution {
    _1d("1d"), _1w("1w"), _1mo("1mo"), _3mo("3mo"), 
    _6mo("6mo"), _1y("1y"), _3y("3y"), _5y("5y");

    private final String value;
    CandleResolution(String value) { this.value = value; }
    public String getValue() { return value; }

    public static CandleResolution fromValue(String v) {
        for (CandleResolution r : values()) if (r.value.equals(v)) return r;
        throw new IllegalArgumentException("Unknown resolution: " + v);
    }
}
