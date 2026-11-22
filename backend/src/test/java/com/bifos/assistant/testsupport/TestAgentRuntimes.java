package com.bifos.assistant.testsupport;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.bifos.assistant.hermes.application.dto.HermesRunEvent;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.infra.HermesAgentRuntime;
import com.bifos.assistant.hermes.infra.HermesRunEventKinds;
import com.bifos.assistant.hermes.infra.HermesRunEventStream;
import com.bifos.assistant.hermes.infra.HermesRunOutcomes;
import com.bifos.assistant.runtime.application.AgentRuntimes;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunResult;
import java.util.List;
import org.mockito.Mockito;

/**
 * 실행을 부르는 클래스를 직접 만드는 테스트가 쓰는 조립이다.
 *
 * <p>가짜 Hermes 호출을 실제 Hermes 어댑터로 감싸, 테스트가 Hermes 의 사건 이름과 상태 문자열로 준비한 값이 운영과 같은 길로
 * 중립 타입으로 옮겨지게 한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestAgentRuntimes {

    public static AgentRuntimes hermes(HermesRunsClient client, HermesRunEventStream stream) {
        return new AgentRuntimes(List.of(new HermesAgentRuntime(client, stream)));
    }


    /** 사건 스트림을 쓰지 않는 테스트용이다. */
    public static AgentRuntimes hermes(HermesRunsClient client) {
        return hermes(client, Mockito.mock(HermesRunEventStream.class));
    }


    public static RunResult result(HermesRunResult result) {
        return HermesRunOutcomes.toRunResult(result);
    }


    public static RunEvent event(HermesRunEvent event) {
        return HermesRunEventKinds.toRunEvent(event);
    }
}
