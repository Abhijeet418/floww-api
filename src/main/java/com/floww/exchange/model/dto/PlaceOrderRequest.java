package com.floww.exchange.model.dto;

import com.floww.exchange.model.enums.OrderSide;
import com.floww.exchange.model.enums.OrderType;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PlaceOrderRequest {
    @NotBlank private String clientOrderId;
    @NotBlank private String ticker;
    @NotNull  private OrderSide side;
    @NotNull  private OrderType type;
    private Long price;
    @Positive private long qty;
    @NotBlank private String traderId;
}
