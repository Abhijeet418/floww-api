package com.floww.exchange.model.dto;

import com.floww.exchange.model.enums.TickerStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TickerStatusUpdateRequest {
    @NotNull private TickerStatus status;
    private String reason;
}
