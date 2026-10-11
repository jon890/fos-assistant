package com.bifos.assistant.mcp.application;

import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 호출 시점의 설정과 각 도구 서비스의 정의를 기존 순서로 모은다. 실행 handler는 갖지 않는다. */
@Service
@RequiredArgsConstructor
public class McpToolCatalog {
    private final LiveProperties<DelegationProperties> delegationProperties;
    private final McpMemoryRemember memoryRemember;
    private final McpMediaObservationTools mediaObservations;


    public List<Map<String, Object>> tools() {
        return List.of(
            Map.of(
                "name",
                "memory_read",
                "description",
                "지금 묻는 사람의 Memory 항목 본문을 번호로 읽는다. 번호는 지시문의 개인 사실 구역이나 색인에 있다.",
                "inputSchema",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("id", Map.of("type", "integer")),
                    "required",
                    List.of("id"))),
            Map.of(
                "name",
                "artifact_write",
                "description",
                "대화의 결과물에 쓸 파일을 저장한다. conversation_id에는 대화 UUID, path에는 폴더 안 상대 경로를 준다. content로 html 또는 css 본문을 쓰거나 source_url로 png, jpg, jpeg, gif, webp 이미지를 가져온다. 두 방식은 하나만 쓰며 파일 하나는 5MB를 넘을 수 없다. 같은 path는 새 내용으로 바뀐다.",
                "inputSchema",
                Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "properties",
                    Map.of(
                        "conversation_id", Map.of("type", "string"),
                        "path", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "source_url", Map.of("type", "string")),
                    "required", List.of("conversation_id", "path"),
                    "oneOf",
                    List.of(
                        Map.of(
                            "required",
                            List.of("content"),
                            "not",
                            Map.of("required", List.of("source_url"))),
                        Map.of(
                            "required",
                            List.of("source_url"),
                            "not",
                            Map.of("required", List.of("content")))))),
            Map.of(
                "name",
                "agent_list",
                "description",
                "지금 묻는 사람이 일을 맡길 수 있는 에이전트의 code 와 이름을 읽는다.",
                "inputSchema",
                Map.of("type", "object", "additionalProperties", false, "properties", Map.of())),
            Map.of(
                "name",
                "agent_delegate",
                "description",
                "다른 에이전트에게 일을 맡기고 실행 번호를 바로 돌려받는다. 끝날 때까지 기다리지 않는다. agent_code 에는 agent_list 로 받은 code 를, task 에는 그 에이전트에게 줄 지시를 넣는다. 맡긴 뒤 남은 일을 계속하고, 할 일이 끝나면 맡긴 일을 알리고 답을 마친다. 맡긴 일이 끝나면 그 결과가 이 대화의 다음 차례에 자동으로 전달된다.",
                "inputSchema",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "properties",
                    Map.of(
                        "agent_code", Map.of("type", "string"),
                        "task", Map.of("type", "string", "minLength", 1, "maxLength", McpToolService.TASK_MAX_CHARS)),
                    "required",
                    List.of("agent_code", "task"))),
            Map.of(
                "name",
                "agent_status",
                "description",
                "다른 에이전트에게 맡긴 실행의 지금 상태와 결과를 읽는다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 결과는 끝나면 자동으로 전달되므로 기다리려고 반복해서 부르지 않는다. 사용자가 진행 상황을 물을 때 한 번 부른다. 먼저 살펴보기에서는 wait_seconds 로 끝나기를 기다린다(최대 "
                    + delegationProperties.current().statusWaitMax().toSeconds() + "초).",
                "inputSchema",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "properties",
                    Map.of(
                        "execution_id", Map.of("type", "integer"),
                        "wait_seconds", Map.of("type", "integer", "minimum", 0)),
                    "required",
                    List.of("execution_id"))),
            Map.of(
                "name",
                "agent_stop",
                "description",
                "다른 에이전트에게 맡긴 실행 하나를 멈추고 그 뒤의 상태를 돌려준다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 그 실행이 다시 맡긴 실행은 멈추지 않는다. 이미 끝난 실행은 끝난 상태를 그대로 돌려준다. 중지를 요청했는데 아직 RUNNING 이면 stop_requested 가 true 이고, 결과는 agent_status 로 다시 읽는다. stop_requested 가 없는 RUNNING 은 멈추지 못한 것이다.",
                "inputSchema",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "properties",
                    Map.of("execution_id", Map.of("type", "integer")),
                    "required",
                    List.of("execution_id"))),
            Map.of(
                "name",
                "follow_up_propose",
                "description",
                "사용자가 나중에 해야 하거나 끝나기를 기다리는 일을 할 일로 제안한다. 사용자가 받아들여야 챙긴다. 대화에서 분명히 나온 후속 작업만 제안하고, 같은 대화에서 거절한 것은 다시 제안하지 않는다. due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.",
                "inputSchema",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "properties",
                    Map.of(
                        "title",
                        Map.of(
                            "type",
                            "string",
                            "minLength",
                            1,
                            "maxLength",
                            FollowUpService.TITLE_MAX),
                        "due_at",
                        Map.of("type", "string"),
                        "waiting",
                        Map.of("type", "boolean")),
                    "required",
                    List.of("title"))),
            mediaObservations.listDefinition(),
            mediaObservations.recordDefinition(),
            memoryRemember.definition());
    }

}
