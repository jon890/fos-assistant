package com.bifos.assistant.connector.presentation.execution;

import com.bifos.assistant.connector.application.execution.ConnectorGuardSupport;
import com.bifos.assistant.connector.presentation.execution.ConnectorExecutionClaimDtos.SupportRequest;
import com.bifos.assistant.connector.presentation.execution.ConnectorExecutionClaimDtos.SupportResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정확한 support POST에서 서비스 인증과 엄격한 원문 검사를 수행한다. */
@RestController
@RequiredArgsConstructor
public class ConnectorGuardSupportController {
    private final ConnectorGuardSupport support;


    @PostMapping(value = "/internal/connector-executions/support", produces = MediaType.APPLICATION_JSON_VALUE)
    public SupportResponse support(HttpServletRequest request) throws IOException {
        support.authenticate(request.getHeader("Authorization"));
        var input = SupportRequest.decode(request.getInputStream().readNBytes(SupportRequest.REQUEST_LIMIT + 1));
        support.verify(Long.valueOf(input.bindingId()), Long.valueOf(input.connectionId()),
            Instant.parse(input.connectionUpdatedAt()), input.profile(), input.connectorId(),
            input.protocol(), input.manifestSha256());
        return SupportResponse.from(input);
    }
}
