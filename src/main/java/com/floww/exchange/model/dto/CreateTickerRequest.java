package com.floww.exchange.model.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateTickerRequest {
    @NotBlank @Size(max = 10) private String symbol;
    @NotBlank @Size(max = 255) private String name;
    @Positive private long lotSize = 1;
    private Long initialPrice;
}
