package com.bifos.assistant.testsupport;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 브라우저 검사에서 사용량 화면의 종료 상태를 준비한다. */
@RestController
@RequestMapping("/api/v1/test-support/usage")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")
public class UsageTestSupportController {

    private final AgentExecutionRepository executions;

    /** 가장 최근 실행을 고아 실행으로 표시한다. */
    @PostMapping("/last-execution/orphaned")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void orphanLastExecution() {
        AgentExecution execution = executions
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")))
                .stream()
                .findFirst()
                .orElseThrow();
        execution.markFailed("ORPHANED", Instant.now());
        executions.save(execution);
    }
}
