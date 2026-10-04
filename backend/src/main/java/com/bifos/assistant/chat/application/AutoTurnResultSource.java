package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.AutoTurnResult;
import java.time.Instant;
import java.util.List;

/**
 * 위임 결과 말고 자동 turn 으로 대화에 전할 결과를 내는 쪽이다(ADR-040, ADR-050).
 *
 * <p>{@code chat} 은 구현을 모른다. 승인한 동작의 결과처럼 다른 패키지가 가진 결과를 이 모양으로 받아, 위임 결과와
 * 같은 자동 turn 에 모아 전한다. 따로 열면 연속 상한을 두 배로 쓴다. 구현이 하나도 없어도 깨우기는 돈다.
 */
public interface AutoTurnResultSource {

    /**
     * 전달 묶음의 항목에 적는 출처 이름이다(ADR-075). 40자 안의 대문자 이름이다.
     *
     * <p>{@code "DELEGATION"} 은 위임 결과가 쓰므로 쓰지 못한다. 구현마다 다른 이름이어야 한다. 한 결과는
     * {@code (출처 이름, 결과 이름)} 으로 한 묶음에만 든다.
     */
    String source();

    /** 그 대화에 아직 전하지 않은 결과다. 생긴 순서다. */
    List<AutoTurnResult> undelivered(Long conversationId);

    /**
     * 전했다고 적는다. 알림 줄을 저장하는 트랜잭션 안에서 부른다.
     *
     * @param keys {@link AutoTurnResult#key()} 들
     */
    void markDelivered(List<String> keys, Instant now);

    /**
     * 이미 전한 결과를 열쇠로 다시 읽는다. 사용자가 결과를 다시 전달할 때 부른다(ADR-075).
     *
     * <p>그 대화와 그 사용자의 결과만 {@code keys} 의 순서로 낸다. 없거나 남의 것이거나 읽지 못하는 열쇠는 뺀다. 결과를 낸
     * 쪽의 일(외부 호출)은 다시 하지 않고 저장된 결과만 읽는다.
     *
     * @param keys 전달 묶음의 항목에 적힌 결과 이름들
     */
    List<AutoTurnResult> resultsFor(Long conversationId, Long userId, List<String> keys);

    /** 전하지 않은 결과가 있는 대화들이다. 기동할 때 훑는다. */
    List<Long> conversationsWithUndelivered();
}
