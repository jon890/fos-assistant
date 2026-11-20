package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.turn.application.TurnQuestions;
import com.bifos.assistant.followup.application.FollowUpAgentRequests;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpFinish;
import com.bifos.assistant.followup.application.dto.FollowUpProposalOutcome;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.mcp.application.dto.McpCaller;
import com.bifos.assistant.proactive.check.application.ProactiveCheckGuard;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionEventRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 요청자의 챙기는 할 일을 읽고 끝내는 MCP 도구와, 할 일을 바로 바꿔도 되는 실행인지의 판정이다(스펙 service-concepts
 * REQ11, REQ12). 계약은 {@code docs/features/service-concepts/design.md} 의 「PR3 할 일과 기억 도구」 가 갖는다.
 *
 * <p>제목은 로그에 남기지 않는다. 결과의 제목은 모델이 쓴 글일 수 있어 바깥 글 표지로 감싼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpFollowUpTools {
    public static final String LIST = "follow_up_list";
    public static final String DONE = "follow_up_done";
    static final String NOT_DIRECTED = "이 실행은 할 일을 끝내지 못한다. 바꾸지 않았다. 끝냈다고 말하지 않고, 사용자에게 끝났는지 묻고 /now 에서 끝낼 수 있다고 안내한다.";
    static final String UNAVAILABLE = "할 일을 지금 다루지 못했다. 했다고 말하지 않고, 잠시 뒤 다시 부탁하거나 /now 화면에서 하라고 안내한다.";
    /**
     * 할 일을 바로 바꾸는 판정이 바깥 글을 읽지 않는다고 보는 도구다. 기억의 바로 저장 판정이 쓰는 목록에 할 일 목록과 끝내기를 더한다.
     * 더하지 않으면 목록을 읽은 대화가 바깥 글을 읽은 대화가 되어 「그거 끝냈어」 를 끝내지 못한다. 기억의 판정은 넓히지 않는다
     * (스펙 DECISION5).
     */
    private static final Set<String> INTERNAL_TOOLS = Stream.concat(
        McpMemoryRemember.INTERNAL_TOOLS.stream(),
        Stream.of("mcp__fos_assistant__" + LIST, "mcp__fos_assistant__" + DONE)).collect(Collectors.toUnmodifiableSet());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final FollowUpAgentRequests requests;
    private final FollowUpService followUps;
    private final TurnQuestions turnQuestions;
    private final ExecutionEventRepository executionEvents;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final ProactiveCheckGuard checkGuard;


    /**
     * 사용자가 직접 시킨 할 일을 바로 만들거나 끝내도 되는 실행인가(스펙 DECISION5, DECISION16, DECISION31).
     *
     * <p>참인 실행은 둘이다. 사람이 보낸 메시지의 루트 실행이고 그 대화가 바깥 글을 읽지 않았거나, 예약 작업의 발화로 도는 루트
     * 실행이다. 예약 작업은 지시문이 사용자가 쓴 글이라 바깥 글을 읽었어도 참이다. 맡긴 실행, 먼저 살펴보기 트리, 점검 대화의 실행,
     * 대화가 없는 실행은 거짓이다.
     *
     * <p>바깥 글은 {@code memory_remember} 의 바로 저장 판정이 쓰는 도구 목록에 할 일 목록과 끝내기를 더해 대화 전체를 본다. Hermes session 이 앞 turn 의
     * 도구 결과를 이력으로 이어 가기 때문이다.
     */
    boolean userDirected(McpCaller caller) {
        AgentExecution origin = caller.originExecution();
        if (origin.parentExecutionId() != null || origin.conversationId() == null || checkGuard.isCheckTree(origin)) {
            return false;
        }
        if (turnQuestions.questionOf(origin.id(), caller.user().id()).isPresent()) {
            return !executionEvents.existsOutsideToolStartInConversation(origin.conversationId(), INTERNAL_TOOLS);
        }
        // 사람의 질문이 없는 루트 실행 가운데 사람이 없는 실행으로 적힌 것이 예약 작업의 발화와 그 결과를 전하는 turn 이다.
        // 살펴보기의 결과를 전하는 turn 도 같은 표시라 점검 대화는 뺀다.
        return executions.countUnattendedIn(List.of(origin.id())) > 0
            && conversations.findById(origin.conversationId())
                .map(Conversation::purpose)
                .filter(purpose -> purpose != ConversationPurpose.CHECK)
                .isPresent();
    }


    /**
     * origin 실행의 대화에 할 일을 남긴다. 바로 챙기는 줄이나 제안이다.
     *
     * @param direct 사용자가 시켰고 {@link #userDirected} 가 참인가
     */
    FollowUpProposalOutcome save(McpCaller caller, String title, Instant dueAt, boolean waiting, boolean direct) {
        AgentExecution origin = caller.originExecution();
        return direct
            ? requests.openNow(caller.user(), origin.conversationId(), origin.id(), title, dueAt, waiting)
            : followUps.propose(caller.user(), origin.conversationId(), origin.id(), title, dueAt, waiting);
    }


    public Map<String, Object> list(McpCaller caller) {
        try {
            List<Map<String, Object>> items = requests.openOf(caller.user()).stream().map(McpFollowUpTools::item).toList();
            return McpToolService.result(ExternalData.wrap(JSON.writeValueAsString(items)), false);
        } catch (RuntimeException ex) {
            return unavailable(caller, ex);
        }
    }


    /** @param ref {@code follow_up_list} 의 id 나 제목 */
    public Map<String, Object> done(McpCaller caller, String ref) {
        try {
            if (!userDirected(caller)) {
                return failed("not_directed", NOT_DIRECTED, List.of());
            }
            FollowUpFinish finished = requests.finish(caller.user(), ref);
            return switch (finished.status()) {
                case DONE -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "done");
                    body.put("message", "할 일을 끝냈다. 화면에는 끝낸 할 일을 되돌리는 단추가 없다. 사용자가 잘못 끝냈다고 하면 "
                        + "follow_up_propose 에 이 title 과 due_at 을 그대로, requested 를 true 로 주어 다시 적는다.");
                    body.put("follow_up", item(finished.matches().getFirst()));
                    body.put("link", "/now");
                    yield McpToolService.result(ExternalData.wrap(JSON.writeValueAsString(body)), false);
                }
                case AMBIGUOUS -> failed("ambiguous", "맞는 할 일이 여럿이라 바꾸지 않았다. 어느 것인지 사용자에게 되묻고 그 id 로 다시 부른다.",
                    finished.matches());
                case NOT_FOUND -> failed("not_found",
                    "챙기는 할 일 가운데 그런 것이 없다. follow_up_list 로 확인한다. 아직 받아들이지 않은 제안은 /now 에서 받아들인 뒤 끝낸다.",
                    List.of());
            };
        } catch (RuntimeException ex) {
            return unavailable(caller, ex);
        }
    }


    public List<Map<String, Object>> definitions() {
        return List.of(
            definition(LIST,
                "요청자가 챙기는 할 일(「지금 볼 것」 /now 의 「할 것」)을 읽는다. 할 일마다 id, title, due_at, waiting 을 준다. "
                    + "사용자가 무엇을 끝냈다고 했는데 어느 할 일인지 모르면 먼저 이것을 부른다. 받아들이지 않은 제안은 나오지 않는다.",
                Map.of(), List.of()),
            definition(DONE,
                "사용자가 할 일을 끝냈다고 말하면(「그거 끝냈어」, 「병원 예약했어」) 그 할 일을 바로 끝낸다. 사용자가 끝냈다고 하지 않았는데 "
                    + "스스로 판단해 부르지 않는다. follow_up 에는 follow_up_list 의 id 나 제목을 준다. 맞는 할 일이 여럿이면 status ambiguous 와 "
                    + "후보만 돌려주고 바꾸지 않으므로 사용자에게 어느 것인지 되묻는다. 끝낸 뒤 답에 무엇을 끝냈는지 적고 /now 링크를 붙인다. "
                    + "끝낸 할 일은 화면에서 되돌리지 못한다. 잘못 끝냈다고 하면 결과의 제목과 기한으로 다시 적는다. "
                    + "isError 면 끝내지 못한 것이다. 끝냈다고 말하지 않고 message 대로 한다.",
                Map.of("follow_up", Map.of("type", "string", "minLength", 1)), List.of("follow_up")));
    }


    private Map<String, Object> unavailable(McpCaller caller, RuntimeException ex) {
        log.warn("follow-up tool failed executionId={} kind={}", caller.executionId(), ex.getClass().getSimpleName());
        return McpToolService.result(JSON.writeValueAsString(Map.of("status", "unavailable", "message", UNAVAILABLE)), true);
    }


    private static Map<String, Object> failed(String status, String message, List<FollowUpSnapshot> candidates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        if (candidates.isEmpty()) {
            return McpToolService.result(JSON.writeValueAsString(body), true);
        }
        body.put("candidates", candidates.stream().map(McpFollowUpTools::item).toList());
        return McpToolService.result(ExternalData.wrap(JSON.writeValueAsString(body)), true);
    }


    private static Map<String, Object> item(FollowUpSnapshot row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.publicId().toString());
        item.put("title", row.title());
        if (row.dueAt() != null) {
            item.put("due_at", DUE.format(row.dueAt().atZone(ZONE)) + " (Asia/Seoul)");
        }
        item.put("waiting", row.waiting());
        return item;
    }


    private static Map<String, Object> definition(
        String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", required);
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }
}
