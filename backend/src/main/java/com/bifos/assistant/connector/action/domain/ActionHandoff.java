package com.bifos.assistant.connector.action.domain;

import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 플러그인이 그 호출에서 사람에게 「내 브라우저」 화면을 넘긴 기록이다(ADR-20261111 / connector-handoff). 판정 줄의
 * {@code handoff_*} 칸으로 저장하고, 넘기지 않은 줄은 칸이 모두 비어 있다(판정 줄의 {@code handoff} 가 null).
 *
 * <p>플러그인 알림이 낸 넘기기는 {@code ConnectorHandoffRepository} 의 조건부 갱신으로 쓰고, 금융 승인의 결과 판정이 낸 결제와 탭 확인은
 * 잠근 승인 줄에 {@link ConnectorAction#handOff} 로 쓴다. 뜻은 {@code backend/docs/data-schema.md} 의
 * 「connector_action」 이 갖는다.
 */
@Embeddable
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionHandoff {

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_need", length = 20)
    private HandoffNeed need;

    /** 플러그인이 남긴 탭의 CDP target id 다. */
    @Column(name = "handoff_tab", length = 128)
    private String tab;

    @Column(name = "handoff_at")
    private Instant at;

    /** 그 대화의 다음 turn 이 시작돼 카드를 닫은 시각이다. 열려 있으면 null 이다. */
    @Column(name = "handoff_closed_at")
    private Instant closedAt;


    ActionHandoff(HandoffNeed need, String tab, Instant at) {
        this.need = need;
        this.tab = tab;
        this.at = at;
    }
}
