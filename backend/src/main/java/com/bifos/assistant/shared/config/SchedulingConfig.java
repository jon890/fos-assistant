package com.bifos.assistant.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 일정 실행을 켠다.
 *
 * <p>각 일정의 cron 은 설정으로 받는다. 검사에서는 그 값을 {@code -} 로 두어 기동할 때마다 함께 돌지
 * 않게 한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
