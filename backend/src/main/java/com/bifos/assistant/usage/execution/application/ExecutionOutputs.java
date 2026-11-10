package com.bifos.assistant.usage.execution.application;

import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 위임 답 본문({@code agent_execution.output_text})을 저장할 때 암호화하고 읽을 때 푼다(ADR-20261008 / data-encryption).
 *
 * <p>AAD 는 {@code agent_execution:<실행 번호>:output:user:<실행 주인>} 이다. 데이터베이스에서 암호문을 다른 줄로 옮기거나
 * 실행의 주인을 바꾸면 풀리지 않고 {@link #UNREADABLE_OUTPUT} 이 보인다. 풀지 못한 답을 null 로 돌리지 않는다. null 이면
 * {@code agent_status} 가 답이 없는 실행으로 판정한다.
 *
 * <p>실행 줄은 시작할 때 이미 저장돼 번호가 있으므로 답을 적는 때 바로 암호화한다. 암호화가 꺼져 있거나 이 결정 앞의 줄은
 * 평문이다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionOutputs {

    /** 암호문을 풀지 못했을 때 답 자리에 보이는 글이다. 메시지의 같은 글과 뜻이 같다. */
    public static final String UNREADABLE_OUTPUT = "읽을 수 없는 메시지입니다.";

    private final TextCipher cipher;


    /**
     * 끝난 답을 실행 줄에 적는다. 암호화가 켜져 있으면 암호문으로, 꺼져 있으면 평문으로 적는다.
     *
     * @param plain 적을 답. null 이면 적지 않는다
     */
    public void record(AgentExecution execution, String plain) {
        if (plain == null) {
            return;
        }
        if (!cipher.enabled()) {
            execution.recordOutput(plain);
            return;
        }
        if (execution.id() == null) {
            throw new IllegalStateException("execution must be saved before its output is sealed");
        }
        SealedText sealed = cipher.seal(execution.userId(), aad(execution, execution.userId()), plain)
            .orElseThrow(() -> new IllegalStateException("output encryption is not enabled"));
        execution.recordSealedOutput(sealed.content(), sealed.keyId());
    }


    /**
     * 답을 낸다. 암호문이면 풀고, 풀지 못하면 {@link #UNREADABLE_OUTPUT} 이다.
     *
     * @return 평문 줄이면 저장된 글 그대로. 답이 없으면 null
     */
    public String open(AgentExecution execution) {
        if (execution.outputKeyId() == null) {
            return execution.outputText();
        }
        Optional<String> opened = execution.outputText() == null || execution.userId() == null
            ? Optional.empty()
            : cipher.open(
                execution.outputKeyId(),
                execution.userId(),
                aad(execution, execution.userId()),
                execution.outputText());
        return opened.orElseGet(() -> {
            log.warn("위임 답 본문을 풀지 못했다 executionId={}", execution.id());
            return UNREADABLE_OUTPUT;
        });
    }


    private static String aad(AgentExecution execution, Long ownerUserId) {
        return "agent_execution:" + execution.id() + ":output:user:" + ownerUserId;
    }
}
