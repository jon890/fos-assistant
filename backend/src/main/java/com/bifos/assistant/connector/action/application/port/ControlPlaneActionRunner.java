package com.bifos.assistant.connector.action.application.port;

import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Map;

/**
 * 사용자가 승인한 Control Plane 동작을 한 번 하는 길이다(서비스 개념 설계의 「지우기 승인 카드」). 구현은 그 데이터를 가진 기능이
 * 갖는다. 예약 작업 지우기는 {@code task} 가 구현하고 {@code connector} 는 {@code task} 를 import 하지 않는다.
 *
 * <p>승인 줄을 잠근 트랜잭션 안에서 부른다. 대상이 없는 것처럼 예상한 실패는 예외가 아니라 결과로 돌려준다. 저장소 예외처럼
 * 예상하지 못한 예외는 그대로 나가 승인 전체가 되돌려지고, 카드는 기다리는 채로 남아 사용자가 다시 승인할 수 있다.
 */
public interface ControlPlaneActionRunner {

    /** 맡는 동작의 도구 이름이다. {@code ControlPlaneActions} 의 이름과 같다. */
    String toolName();


    /**
     * @param user 승인한 사용자. 승인 줄의 주인이다
     * @param args 카드에 보인 인자. 저장 해시와 맞는 것만 받는다
     */
    ControlPlaneActionOutcome run(CurrentUser user, Map<String, String> args);
}
