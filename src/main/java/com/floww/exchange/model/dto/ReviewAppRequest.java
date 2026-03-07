package com.floww.exchange.model.dto;

import com.floww.exchange.model.enums.AppStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewAppRequest {
    @NotNull
    private AppStatus status;
    private Integer rateLimit;
    private String adminNotes;
}
