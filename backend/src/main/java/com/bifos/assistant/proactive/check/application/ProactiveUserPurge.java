package com.bifos.assistant.proactive.check.application;

import com.bifos.assistant.proactive.check.infra.ProactiveCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 먼저 살펴보기 점검을 지운다(ADR-20261101 / user-purge).
 *
 * <p>점검에 딸린 줄은 외래 키가 함께 지운다. 부르는 쪽의 정리
 * 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProactiveUserPurge {

    private final ProactiveCheckRepository checks;


    /**
     * 그 사용자의 점검을 모두 지운다. 없으면 0 이고 다시 불러도 같다.
     *
     * @return 지운 행 수
     */
    public int purgeUser(Long userId) {
        return checks.deleteAllOfUser(userId);
    }
}
