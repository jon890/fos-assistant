package com.bifos.assistant.chat.nextturn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 답이 도는 중 입력창 글을 도는 실행에 끼워 넣는(steer) 설정이다(ADR-20261116 / run-steer).
 *
 * <p>요청 제한 시간({@code assistant.chat.steer.timeout})은 Hermes 클라이언트가 한 곳에서 읽는다.
 *
 * @param enabled 꺼 두면 Hermes 에 steer 를 보내지 않고 지금 대기열로만 간다. 배포 뒤 운영 왕복을 확인하고 켠다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.chat.steer")
public record SteerProperties(@DefaultValue("false") boolean enabled) {
}
