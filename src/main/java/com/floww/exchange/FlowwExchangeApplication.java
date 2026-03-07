package com.floww.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlowwExchangeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowwExchangeApplication.class, args);
    }
}
