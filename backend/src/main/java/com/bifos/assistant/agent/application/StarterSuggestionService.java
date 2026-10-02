package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 새 대화 화면의 추천 질문을 사용자와 에이전트마다 모델로 만들어 메모리에 둔다.
 *
 * <p>추천은 데이터베이스에 쓰지 않는다. 재시작하면 비고, 화면이 다시 읽으면 새로 만든다(ADR-036).
 * 모델 호출이 몇 초 걸리므로 화면 요청 안에서 만들지 않고 전용 실행기에서 따로 돌린다. 같은 사용자와
 * 에이전트의 만들기는 하나만 돈다. 실패하면 이전 추천을 그대로 두고, {@code retryAfterFailure} 동안은 그
 * 키를 다시 만들지 않는다.
 *
 * <p>추천 실행은 그 에이전트의 profile 로 돈다. Hermes 가 그 profile 의 성격과 켜진 도구, 스킬 색인을
 * 입력에 싣기 때문에 여기서 따로 읽어 넣지 않는다.
 */
@Service
@Slf4j
public class StarterSuggestionService {

    /** 추천을 만드는 입력의 첫 줄. 가짜 Hermes 가 이 표지를 보고 답을 고른다. */
    public static final String PROMPT_MARK = "[추천 질문 만들기]";

    /** 화면에 보이는 추천의 최대 수. */
    static final int MAX_PROMPTS = 4;

    /** 추천 한 줄의 상한. 넘는 줄은 버린다. */
    static final int MAX_PROMPT_CHARS = 120;

    /** 이력으로 넣는 첫 질문 한 줄의 상한. 긴 붙여넣기 하나가 입력을 키우지 않게 자른다. */
    private static final int MAX_HISTORY_CHARS = 200;

    private static final String INVALID_OUTPUT = "STARTER_OUTPUT_INVALID";
    private static final String GENERATION_FAILED = "STARTER_GENERATION_FAILED";

    private final StarterProperties properties;
    private final AgentService agents;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final HermesRunsClient hermes;
    private final ExecutionRecorder executions;
    private final ModelTierService modelTiers;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor executor;

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();
    private final Set<Key> generating = ConcurrentHashMap.newKeySet();
    private final Map<Key, Instant> lastFailures = new ConcurrentHashMap<>();

    /** 캐시 키. 사용자마다 그 에이전트로 하는 일이 달라 둘을 함께 쓴다. */
    private record Key(Long userId, Long agentId) {}

    /** 만든 추천과 만든 시각. */
    private record Entry(List<String> prompts, Instant generatedAt) {}

    @Autowired
    public StarterSuggestionService(
            StarterProperties properties,
            AgentService agents,
            ConversationRepository conversations,
            ChatMessageRepository messages,
            HermesRunsClient hermes,
            ExecutionRecorder executions,
            ModelTierService modelTiers,
            ObjectMapper objectMapper) {
        this(
                properties,
                agents,
                conversations,
                messages,
                hermes,
                executions,
                modelTiers,
                objectMapper,
                Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 시각과 실행기를 바꿔 끼운다. 테스트가 시간을 옮기고 만들기가 끝나기를 기다릴 때 쓴다. */
    public StarterSuggestionService(
            StarterProperties properties,
            AgentService agents,
            ConversationRepository conversations,
            ChatMessageRepository messages,
            HermesRunsClient hermes,
            ExecutionRecorder executions,
            ModelTierService modelTiers,
            ObjectMapper objectMapper,
            Clock clock,
            Executor executor) {
        this.properties = properties;
        this.agents = agents;
        this.conversations = conversations;
        this.messages = messages;
        this.hermes = hermes;
        this.executions = executions;
        this.modelTiers = modelTiers;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * 요청자가 그 에이전트에서 받을 추천을 돌려준다. 추천이 없으면 만들기를 시작하고 곧바로 돌아온다.
     *
     * <p>꺼 둔 에이전트는 새 실행을 막으므로 만들기를 시작하지 않고 추천이 없다고 답한다. 새 대화 화면이 읽는
     * 경로라 오류로 돌려주지 않는다.
     *
     * @throws ApiException 볼 수 없거나 없는 에이전트면 {@link ErrorCode#AGENT_NOT_FOUND}
     */
    public StarterSuggestions read(CurrentUser user, String code) {
        Agent agent = agents.requireReadable(user, code);
        if (!properties.enabled() || !agent.enabled()) {
            return none();
        }
        Key key = new Key(user.id(), agent.id());
        Entry cached = cache.get(key);
        if (cached != null) {
            return ready(cached);
        }
        if (generating.contains(key)) {
            return generating();
        }
        if (recentlyFailed(key)) {
            return none();
        }
        if (launch(key, user, agent, entry -> entry == null)) {
            return generating();
        }
        // 확인한 사이에 다른 요청이 만들기를 시작했거나 끝냈다. 지금 상태를 다시 읽는다.
        return current(key);
    }

    /**
     * 대화를 마쳤을 때 부른다. 추천이 있고 {@code refreshAfter} 보다 오래됐을 때만 다시 만든다.
     *
     * <p>추천이 없거나 에이전트가 꺼져 있으면 아무것도 하지 않는다. 처음 만드는 것은 화면이 읽을 때다.
     * turn 을 마치는 흐름을 실패시키지 않도록 예외를 밖으로 내지 않는다.
     */
    public void refreshIfStale(CurrentUser user, Agent agent) {
        try {
            if (!properties.enabled() || !agent.enabled()) {
                return;
            }
            Key key = new Key(user.id(), agent.id());
            Entry cached = cache.get(key);
            if (cached == null || !isStale(cached) || recentlyFailed(key)) {
                return;
            }
            launch(key, user, agent, entry -> entry != null && isStale(entry));
        } catch (RuntimeException ex) {
            log.warn("추천 질문을 다시 만들기 시작하지 못했다 userId={} agentId={}", user.id(), agent.id(), ex);
        }
    }

    /**
     * 그 키의 만들기를 시작한다. 이미 도는 것이 있거나 조건이 맞지 않으면 시작하지 않는다.
     *
     * <p>진행 중 표시를 먼저 잡은 뒤 조건을 다시 본다. 표시를 잡기 전에 다른 만들기가 끝났을 수 있기 때문이다.
     *
     * @return 시작했으면 참
     */
    private boolean launch(Key key, CurrentUser user, Agent agent, Predicate<Entry> shouldGenerate) {
        if (!generating.add(key)) {
            return false;
        }
        try {
            if (!shouldGenerate.test(cache.get(key)) || recentlyFailed(key)) {
                generating.remove(key);
                return false;
            }
            executor.execute(() -> generate(key, user, agent));
            return true;
        } catch (RuntimeException ex) {
            generating.remove(key);
            throw ex;
        }
    }

    /** 이력을 읽어 Hermes 실행 하나를 돌리고 답을 캐시에 넣는다. 어느 경우든 진행 중 표시를 지운다. */
    private void generate(Key key, CurrentUser user, Agent agent) {
        AgentExecution execution = null;
        try {
            String input = prompt(firstQuestions(user, agent));
            ModelChoice choice = modelTiers.detachedChoice(agent);
            execution = executions.startDetached(user, agent, choice);
            // 실행 줄을 먼저 만들고 숨김을 판정한다. 거절해도 그 오류 코드로 실패한 줄이 남는다.
            modelTiers.requireRunnable(user, agent, choice);
            // 대화가 없는 실행이라 에이전트 기본 모델로 돈다. 비어 있으면 profile 의 값이다(ADR-054).
            HermesRunCommand command = new HermesRunCommand(
                    agent.hermesProfile(),
                    agent.apiBaseUrl(),
                    input,
                    null,
                    null,
                    choice.provider(),
                    choice.model(),
                    choice.reasoningEffort());
            String runId = hermes.submit(command);
            executions.attachRunId(execution, runId);
            HermesRunResult result = hermes.awaitCompletion(command, runId);
            if (!result.succeeded()) {
                executions.fail(
                        execution,
                        agent,
                        result,
                        null,
                        result.providerBlocked() ? ErrorCode.PROVIDER_BLOCKED.name() : statusOf(result));
                lastFailures.put(key, clock.instant());
                return;
            }
            Optional<List<String>> prompts = promptsOf(result.output());
            if (prompts.isEmpty()) {
                executions.fail(execution, agent, result, null, INVALID_OUTPUT);
                lastFailures.put(key, clock.instant());
                return;
            }
            cache.put(key, new Entry(prompts.get(), clock.instant()));
            lastFailures.remove(key);
            // 추천은 이미 만들어 캐시에 넣었다. 실행 줄을 끝내다 실패한 것을 만들기 실패로 번지게 하면 사용자는
            // 추천을 보는데 실행이 실패로 남고 재시도 시간 동안 다시 만들지 못한다. 그래서 로그만 남긴다.
            try {
                executions.complete(execution, agent, result, choice);
            } catch (RuntimeException ex) {
                log.warn("추천은 만들었지만 실행 줄을 끝내지 못했다 executionId={}", execution.id(), ex);
            }
        } catch (Exception ex) {
            if (execution != null) {
                failQuietly(execution, errorCode(ex));
            }
            lastFailures.put(key, clock.instant());
            log.warn("추천 질문을 만들지 못했다 userId={} agentId={}", key.userId(), key.agentId(), ex);
        } finally {
            generating.remove(key);
        }
    }

    /** 그 사용자가 그 에이전트와 나눈 최근 대화마다 처음 꺼낸 말을 모은다. 다른 사용자의 대화는 읽지 않는다. */
    private List<String> firstQuestions(CurrentUser user, Agent agent) {
        List<Conversation> recent = conversations.findByUserIdAndAgentIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                user.id(), agent.id(), PageRequest.of(0, properties.historyConversations()));
        List<String> questions = new ArrayList<>();
        for (Conversation conversation : recent) {
            messages.findFirstByConversationIdAndRoleOrderByIdAsc(conversation.id(), MessageRole.USER)
                    .map(ChatMessage::content)
                    .map(StarterSuggestionService::oneLine)
                    .filter(text -> !text.isEmpty())
                    .ifPresent(questions::add);
        }
        return questions;
    }

    private static String prompt(List<String> questions) {
        StringBuilder input = new StringBuilder(PROMPT_MARK).append('\n');
        if (questions.isEmpty()) {
            input.append("너의 성격과 쓸 수 있는 도구와 스킬로 이 사용자가 처음 해 볼 만한 요청 넷을 만든다.\n");
        } else {
            input.append("사용자가 이 에이전트에게 최근에 처음 꺼낸 말들이다. ").append("자주 하는 일을 묶어 다음에 바로 보낼 만한 요청 넷을 만든다.\n");
            for (String question : questions) {
                input.append("- ").append(question).append('\n');
            }
        }
        input.append("도구를 부르지 말고 JSON 문자열 배열 하나만 답한다.");
        return input.toString();
    }

    /**
     * 모델 답에서 추천을 읽는다. 읽을 것이 없으면 빈 값이다.
     *
     * <p>실제 모델은 답을 코드 펜스로 감싸는 일이 흔해 첫 {@code [} 부터 마지막 {@code ]} 까지만 떼어 읽는다.
     * 문자열이 아닌 원소, 빈 줄, {@link #MAX_PROMPT_CHARS} 를 넘는 줄은 버리고 앞의 {@link #MAX_PROMPTS} 개만
     * 쓴다.
     */
    private Optional<List<String>> promptsOf(String output) {
        if (output == null) {
            return Optional.empty();
        }
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        JsonNode array;
        try {
            array = objectMapper.readTree(output.substring(start, end + 1));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (array == null || !array.isArray()) {
            return Optional.empty();
        }
        List<String> prompts = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isString()) {
                continue;
            }
            String text = node.asString().strip();
            if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_PROMPT_CHARS) {
                continue;
            }
            prompts.add(text);
            if (prompts.size() == MAX_PROMPTS) {
                break;
            }
        }
        return prompts.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(prompts));
    }

    private boolean isStale(Entry entry) {
        return clock.instant().isAfter(entry.generatedAt().plus(properties.refreshAfter()));
    }

    private boolean recentlyFailed(Key key) {
        Instant failedAt = lastFailures.get(key);
        return failedAt != null && clock.instant().isBefore(failedAt.plus(properties.retryAfterFailure()));
    }

    private StarterSuggestions current(Key key) {
        Entry cached = cache.get(key);
        if (cached != null) {
            return ready(cached);
        }
        return generating.contains(key) ? generating() : none();
    }

    private void failQuietly(AgentExecution execution, String code) {
        try {
            executions.fail(execution, code);
        } catch (RuntimeException ex) {
            log.warn("추천 실행을 실패로 남기지 못했다 executionId={}", execution.id(), ex);
        }
    }

    /** 줄바꿈과 겹친 공백을 한 칸으로 줄이고 {@link #MAX_HISTORY_CHARS} 에서 자른다. */
    private static String oneLine(String content) {
        String line = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        if (line.codePointCount(0, line.length()) <= MAX_HISTORY_CHARS) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, MAX_HISTORY_CHARS)) + "…";
    }

    private static StarterSuggestions ready(Entry entry) {
        return new StarterSuggestions(entry.prompts(), StarterStatus.READY);
    }

    private static StarterSuggestions generating() {
        return new StarterSuggestions(List.of(), StarterStatus.GENERATING);
    }

    private static StarterSuggestions none() {
        return new StarterSuggestions(List.of(), StarterStatus.NONE);
    }

    private static String statusOf(HermesRunResult result) {
        return result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
    }

    private static String errorCode(Exception exception) {
        return exception instanceof ApiException api ? api.code().name() : GENERATION_FAILED;
    }
}
