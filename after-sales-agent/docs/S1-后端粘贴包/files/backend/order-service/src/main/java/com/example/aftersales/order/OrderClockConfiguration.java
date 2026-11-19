package com.example.aftersales.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class OrderClockConfiguration {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    @Bean
    Clock businessClock() { return Clock.system(BUSINESS_ZONE); }
}
