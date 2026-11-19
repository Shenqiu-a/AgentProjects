package com.example.aftersales.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

/**
 * 功能：
 * 作者：yml
 * 日期：2026/11/1811:53
 */

@Configuration(proxyBeanMethods = false)
public class OrderClockConfiguration {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    @Bean
    Clock businessClock() { return Clock.system(BUSINESS_ZONE); }
}
