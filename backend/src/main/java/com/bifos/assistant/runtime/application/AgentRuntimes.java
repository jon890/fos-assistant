package com.bifos.assistant.runtime.application;

import com.bifos.assistant.runtime.application.port.AgentRuntime;
import com.bifos.assistant.runtime.domain.RuntimeKind;
import com.bifos.assistant.runtime.domain.RuntimeTarget;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 실행 대상의 런타임 종류로 실행 port 의 구현을 고른다.
 *
 * <p>실행을 부르는 쪽은 구현을 직접 주입받지 않고 여기서 고른다. 다른 런타임으로 바꿔 돌리지 않는다. 맞는 구현이 없으면 실패다.
 */
@Component
public class AgentRuntimes {

    private final Map<RuntimeKind, AgentRuntime> byKind = new EnumMap<>(RuntimeKind.class);


    public AgentRuntimes(List<AgentRuntime> runtimes) {
        for (AgentRuntime runtime : runtimes) {
            if (byKind.putIfAbsent(runtime.kind(), runtime) != null) {
                throw new IllegalStateException("duplicate agent runtime for kind " + runtime.kind());
            }
        }
    }


    /** 그 대상의 런타임 구현이다. 없으면 실행 실패와 같은 오류를 던진다. */
    public AgentRuntime of(RuntimeTarget target) {
        AgentRuntime runtime = byKind.get(target.kind());
        if (runtime == null) {
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "no agent runtime for kind " + target.kind());
        }
        return runtime;
    }
}
