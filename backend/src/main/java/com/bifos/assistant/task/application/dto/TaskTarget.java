package com.bifos.assistant.task.application.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 에이전트의 예약 작업 도구가 대상을 찾은 결과다. 맞는 작업이 하나면 {@code taskId} 가 차고, 이름이 같은 작업이 여럿이면
 * {@code candidates} 가 찬다. 둘 다 비면 없는 작업이다.
 */
public record TaskTarget(UUID taskId, List<Map<String, Object>> candidates) {
}
