package com.bifos.assistant.connector.presentation.execution;

import com.bifos.assistant.connector.application.execution.ConnectorExecutionClaimCodec;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionClaims;
import com.bifos.assistant.connector.presentation.execution.ConnectorExecutionClaimDtos.ClaimResponse;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정확한 claim 경로에서만 본문의 일회성 ticket으로 인증한다. */
@RestController
@RequiredArgsConstructor
public class ConnectorExecutionClaimController {
    private final ConnectorExecutionClaimCodec codec;
    private final ConnectorExecutionClaims claims;

    @PostMapping(value = "/internal/connector-executions/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClaimResponse claim(HttpServletRequest request) throws IOException {
        // Content-Length가 없거나 틀려도 실제 받은 바이트로 상한을 지킨다.
        byte[] raw = request.getInputStream().readNBytes(ConnectorExecutionClaimCodec.REQUEST_LIMIT + 1);
        if (raw.length > ConnectorExecutionClaimCodec.REQUEST_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "financial execution request rejected");
        }
        return ClaimResponse.from(claims.claim(codec.decodeRequest(raw)));
    }
}
