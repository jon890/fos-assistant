package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * 예약 jobId로 기존 커넥터 worker를 한 번 시작하는 전송이다.
 *
 * <p>구현 빈이 없으면 원고 저장 승인은 시작을 지원하지 않는 것으로 보고 실행 없이 거절한다. 자동 재전송은 하지 않는다.
 */
public interface ContentDraftJobStarter {
    /**
     * @param snapshot Control Plane이 재계산해 지문을 확인한 compiled snapshot
     * @param job 예약 jobId 와 snapshot·바인딩 판. private 상태 stamp 를 만드는 데 쓰고 profileHash 는 profile 로 계산한다. 발급·기한 칸은 비어 있다
     * @return 접수했으면 빈 값, 시작 전에 거절됐으면 안전한 오류 code
     * @throws RuntimeException 전송했는지나 접수했는지를 모를 때
     */
    Optional<String> start(String profile, String connectorId, JsonNode snapshot, ContentDraftSaveTicketPayload job, String ticket);
}
