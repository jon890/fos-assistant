package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.approval.application.dto.FinancialPrepareStage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;

/** 금융 준비를 거절한 단계를 담은 거절이다. 오류 코드와 메시지는 {@link ConnectorFinancialProducer#rejected()} 와 같다. */
final class FinancialPrepareRejected extends ApiException {
    private final transient FinancialPrepareStage stage;


    FinancialPrepareRejected(FinancialPrepareStage stage) {
        super(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial preparation unavailable");
        this.stage = stage;
    }


    FinancialPrepareStage stage() {
        return stage;
    }
}
