package com.bifos.assistant.agent.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자가 에이전트를 만들 때 지키는 값이다.
 *
 * <p>기본값을 코드에 둔다. 없다고 기동을 막을 값이 아니다.
 *
 * @param maxPerUser 사용자 한 사람이 가질 수 있는 지우지 않은 에이전트 수. 첫 로그인에 생긴 에이전트도
 *     센다. {@code ADMIN} 은 세지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.agents")
public record AgentProperties(Integer maxPerUser) {

    public AgentProperties {
        maxPerUser = maxPerUser == null ? 5 : maxPerUser;
    }
}
