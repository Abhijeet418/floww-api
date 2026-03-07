package com.floww.exchange.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterAppRequest {
    @NotBlank @Size(max = 255)
    private String name;

    @NotBlank @Email @Size(max = 255)
    private String contactEmail;

    @Size(max = 2000)
    private String description;

    @Size(max = 2048)
    private String webhookUrl;
}
