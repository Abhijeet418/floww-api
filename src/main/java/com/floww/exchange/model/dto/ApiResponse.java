package com.floww.exchange.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String error;
    private String code;
    private String exchangeType;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = true; r.data = data; r.exchangeType = "SIMULATED";
        return r;
    }
    public static <T> ApiResponse<T> error(String msg, String code) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = false; r.error = msg; r.code = code; r.exchangeType = "SIMULATED";
        return r;
    }
}
