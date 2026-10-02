package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 첫 로그인에 만드는 에이전트의 과금 설정이다.
 *
 * <p>Hermes 를 부르는 값이 아니라 {@code HermesProperties} 에 두지 않는다. 두 값이 사람마다 다르지
 * 않은 근거는 {@code docs/adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md} 에 있다.
 * profile 은 사람마다 나누고 AI 계정은 가족이 함께 쓴다.
 *
 * <p>기본값을 코드에 둔다. 없다고 기동을 막을 값이 아니다. 다르게 써야 하면 관리자가 에이전트 관리
 * 화면에서 그 에이전트만 고친다.
 *
 * <p>설정 이름이 {@code assistant.people} 인 까닭은 처음에 {@code people} 이 갖던 설정이기 때문이다.
 *
 * @param defaultCostMode 그 에이전트의 비용 방식
 * @param defaultCredentialScope 그 에이전트가 쓰는 AI 계정의 범위
 */
@Validated
@ConfigurationProperties(prefix = "assistant.people")
public record PeopleProperties(CostMode defaultCostMode, CredentialScope defaultCredentialScope) {

    public PeopleProperties {
        defaultCostMode = defaultCostMode == null ? CostMode.SUBSCRIPTION : defaultCostMode;
        defaultCredentialScope =
                defaultCredentialScope == null ? CredentialScope.SHARED_HOUSEHOLD : defaultCredentialScope;
    }
}
