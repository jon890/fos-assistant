package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** 외부 connector 작업 실패를 원인 본문 없이 고정 응답으로 바꾼다. */
public final class ConnectorOperationFailure extends ApiException {

    public ConnectorOperationFailure() {
        super(ErrorCode.CONNECTOR_OPERATION_FAILED, "connector operation failed");
    }
}
