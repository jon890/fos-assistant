package com.bifos.assistant.hermes.infra;

import com.bifos.assistant.runtime.application.dto.SessionRuntime;

/**
 * 끝난 실행의 상태와 결과 값이다.
 *
 * @param pendingSteerKnown 넘어간 steer 글이 있었는지 아는가. 끝 사건을 얻지 못했거나 정상 종료로 끊긴 실행이면 거짓이다
 */
record HermesRunEnding(
    String status,
    String error,
    String pendingSteer,
    boolean pendingSteerKnown,
    String servedModel,
    String servedProvider) {

    /** 실제로 돈 provider 와 모델이다. 둘 다 모르면 null 이다. */
    SessionRuntime runtime() {
        return servedModel == null && servedProvider == null ? null : new SessionRuntime(servedModel, servedProvider);
    }
}
