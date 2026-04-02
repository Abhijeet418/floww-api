package com.floww.exchange.model.enums;

public enum CandleResolution {
    _1d("1d"), _1w("1w");

    private final String value;
    CandleResolution(String value) { this.value = value; }
    public String getValue() { return value; }

    public static CandleResolution fromValue(String v) {
        for (CandleResolution r : values()) if (r.value.equals(v)) return r;
        throw new IllegalArgumentException("Unknown resolution: " + v);
    }
}
