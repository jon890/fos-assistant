package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.application.AutoTurnResultSource;
import java.util.List;

/**
 * 이 자동 turn 이 전하는 결과를 낸 쪽과 그 결과들의 이름이다. 알림 줄과 같은 트랜잭션에서 전했다고 적는다.
 *
 * @param keys {@link AutoTurnResult#key()} 들
 */
public record AutoTurnDelivery(AutoTurnResultSource source, List<String> keys) {
    public AutoTurnDelivery {
        keys = List.copyOf(keys);
    }
}
