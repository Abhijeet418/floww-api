package com.floww.exchange.controller;

import com.floww.exchange.filter.ApiKeyFilter;
import com.floww.exchange.model.dto.ApiResponse;
import com.floww.exchange.model.dto.OrderResponse;
import com.floww.exchange.model.dto.PlaceOrderRequest;
import com.floww.exchange.service.OrderGatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderGatewayService orderGatewayService;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request, HttpServletRequest httpReq) {
        UUID appId = (UUID) httpReq.getAttribute(ApiKeyFilter.APP_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(orderGatewayService.placeOrder(appId, request)));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable UUID orderId, HttpServletRequest httpReq) {
        UUID appId = (UUID) httpReq.getAttribute(ApiKeyFilter.APP_ID_ATTRIBUTE);
        return ResponseEntity.ok(ApiResponse.ok(orderGatewayService.cancelOrder(appId, orderId)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @PathVariable UUID orderId, HttpServletRequest httpReq) {
        UUID appId = (UUID) httpReq.getAttribute(ApiKeyFilter.APP_ID_ATTRIBUTE);
        return ResponseEntity.ok(ApiResponse.ok(orderGatewayService.getOrder(appId, orderId)));
    }
}
