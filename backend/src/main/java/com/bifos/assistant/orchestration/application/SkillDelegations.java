package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.PrimaryAgents;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.application.ConversationEntry;
import com.bifos.assistant.chat.nextturn.application.SkillDelegationQuestions;
import com.bifos.assistant.chat.nextturn.application.dto.SkillDelegationQuestion;
import com.bifos.assistant.chat.turn.application.dto.DelegatedSkillCommand;
import com.bifos.assistant.chat.turn.application.dto.SkillCommand;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationRun;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationStarted;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationTarget;
import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.application.SkillCommandCatalog;
import com.bifos.assistant.skill.library.application.SkillService;
import com.bifos.assistant.skill.library.application.dto.SkillList;
import com.bifos.assistant.skill.library.application.dto.SkillListItem;
import com.bifos.assistant.skill.library.application.dto.SkillSource;
import com.bifos.assistant.usage.execution.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.execution.application.ExecutionOutputs;
import com.bifos.assistant.usage.execution.application.ExecutionQuestions;
import com.bifos.assistant.usage.execution.application.UserExecutionLimiter;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.DelegationKey;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 전문 에이전트의 스킬을 비서 대화에서 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10, DECISION6, DECISION10, DECISION26, DECISION27).
 *
 * <p>{@code /} 목록에 보일 스킬을 주고, 고른 스킬을 대화 turn 없이 그 에이전트의 실행으로 맡긴다. 실행은 부모 없는 위임 실행이고 결과는 지금
 * 깨우기(ADR-040)가 결과 도착 줄과 비서 답으로 전한다. 예약 작업 지시의 {@code /에이전트 이름 · 스킬} 도 같은 실행을 쓴다. 맡길 수 있는
 * 에이전트는 {@code agent_list} 와 같은 기준(요청자가 쓸 수 있고 켜짐)에서 기본 비서를 뺀 것이다. 차례와 값은
 * {@code docs/features/assistant-hub/design.md} 의 「PR3」 이 갖는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDelegations {
    private final AgentService agents;
    private final PrimaryAgents primaryAgents;
    private final SkillCommandCatalog skillCommands;
    private final SkillService skillService;
    private final ConversationEntry entry;
    private final SkillDelegationQuestions questions;
    private final AgentDelegationService delegations;
    private final ExecutionQuestions executionQuestions;
    private final ExecutionDeliveryWriter executionMarks;
    private final AgentExecutionRepository executions;
    private final ExecutionOutputs outputs;
    private final UserExecutionLimiter limiter;
    private final LiveProperties<DelegationProperties> properties;
    private final BackgroundTasks backgroundTasks;


    /**
     * 맡길 수 있는 전문 에이전트와 그 켜진 스킬이다. 비서의 {@code /} 목록과 같게 화면 스킬 목록에 보이는 스킬(Hermes 기본 스킬은 뺀다) 가운데
     * 켜졌고 커맨드로 부를 수 있는 이름만 둔다. 스킬이 없는 에이전트는 뺀다. 스킬 목록을 읽지 못한 에이전트도 빼고 경고 로그만 남긴다. 한
     * 에이전트가 실패해도 목록 전체를 막지 않기 위해서다.
     *
     * <p>이름이 「에이전트 · 스킬」 글로 다시 읽히지 않거나 같은 이름의 에이전트가 둘 이상이면 뺀다. 화면의 항목이 같은 글이라 어느 쪽인지
     * 고를 수 없고, 예약 작업 지시도 그 이름으로 정하지 못한다(설계 DECISION8).
     */
    public List<SkillDelegationTarget> targets(CurrentUser user) {
        List<Agent> candidates = delegable(user);
        Map<String, Long> sameName = candidates.stream().collect(Collectors.groupingBy(Agent::name, Collectors.counting()));
        List<SkillDelegationTarget> found = new ArrayList<>();
        for (Agent agent : candidates) {
            if (!DelegatedSkillCommand.readsBack(agent.name()) || sameName.get(agent.name()) > 1) {
                continue;
            }
            List<String> names;
            try {
                names = listedNames(skillService.commandList(agent));
            } catch (RuntimeException ex) {
                log.warn("맡길 스킬 목록을 읽지 못해 그 에이전트를 뺐다 agent={}", agent.code(), ex);
                continue;
            }
            if (!names.isEmpty()) {
                found.add(new SkillDelegationTarget(agent.code(), agent.name(), names));
            }
        }
        return found;
    }


    /**
     * 사람이 고른 스킬을 맡긴다. 대화, 에이전트, 스킬, 자리를 차례로 보고, 질문을 남긴 뒤 실행을 띄워 실행 줄이 생기면 돌려준다. 대화 turn
     * 잠금은 잡지 않는다.
     *
     * @param conversationId 이어 쓸 대화. 없으면 기본 비서와 새 대화
     * @param agentCode 맡을 에이전트. 글의 에이전트 이름이 그사이 바뀌어도 이 코드로 정한다
     * @param text {@code /<에이전트 이름> · <스킬> [나머지]}
     */
    public SkillDelegationStarted delegate(CurrentUser user, Long conversationId, String agentCode, String text) {
        DelegatedSkillCommand command = DelegatedSkillCommand.parse(text)
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "the text must start with /<agent> · <skill>"));
        if (conversationId != null) {
            entry.requireWritable(user, conversationId);
        }
        Agent agent = agents.requireStartable(user, agentCode);
        if (primaryAgents.of(user).map(Agent::id).filter(agent.id()::equals).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "use /<skill> for the primary assistant");
        }
        requireSkill(agent, command);
        if (!limiter.hasTurnRoom(user.id())) {
            throw new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit");
        }
        DelegationRuns runs = delegations.runs();
        if (!runs.tryAcquire(properties.current().maxActive())) {
            throw new ApiException(ErrorCode.USER_BUSY, "too many delegations are running");
        }
        SkillDelegationQuestion question;
        try {
            question = questions.ask(user, conversationId, text);
        } catch (RuntimeException ex) {
            runs.release();
            throw ex;
        }
        Handoff handoff = new Handoff();
        try {
            backgroundTasks.start(
                "skill-delegate-" + question.conversation().id(),
                () -> runs.runUnparented(user, question.conversation(), agent, command.command().hermesInput(), newKey(agent),
                    linkQuestion(question, false), handoff));
        } catch (RuntimeException | Error ex) {
            runs.release();
            questions.withdraw(question);
            throw ex;
        }
        long deadline = System.nanoTime() + properties.current().submitTimeout().toNanos();
        if (!handoff.awaitRowDecided(deadline) && handoff.abandon()) {
            questions.withdraw(question);
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "the delegation did not start in time");
        }
        if (!handoff.rowCreated()) {
            questions.withdraw(question);
            throw failureOf(handoff);
        }
        return new SkillDelegationStarted(question.conversation(), handoff.execution().id());
    }


    /**
     * 예약 작업의 지시로 맡기고 끝날 때까지 그 스레드에서 돌린다. 에이전트는 이름으로 찾는다. 같은 이름이 둘이면 찾지 못한 것으로 본다. 실행은 사람이
     * 없는 실행으로 적는다.
     *
     * @return 끝난 실행의 상태와 답
     * @throws ApiException 에이전트가 없으면 {@code AGENT_NOT_FOUND}, 스킬이 켜져 있지 않으면 {@code SKILL_COMMAND_UNKNOWN}. 발화가
     * 실패로 닫힌다
     */
    public SkillDelegationRun runScheduled(CurrentUser user, Long conversationId, String notice, String text) {
        DelegatedSkillCommand command = DelegatedSkillCommand.parse(text)
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "the text must start with /<agent> · <skill>"));
        List<Agent> named = delegable(user).stream().filter(agent -> agent.name().equals(command.agentName())).toList();
        if (named.size() != 1) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no single delegable agent has that name");
        }
        Agent agent = named.getFirst();
        requireSkill(agent, command);
        DelegationRuns runs = delegations.runs();
        if (!runs.tryAcquire(properties.current().maxActive())) {
            throw new ApiException(ErrorCode.USER_BUSY, "too many delegations are running");
        }
        SkillDelegationQuestion question;
        try {
            question = questions.askScheduled(user, conversationId, notice, text);
        } catch (RuntimeException ex) {
            runs.release();
            throw ex;
        }
        Handoff handoff = new Handoff();
        runs.runUnparented(user, question.conversation(), agent, command.command().hermesInput(), newKey(agent),
            linkQuestion(question, true), handoff);
        if (!handoff.rowCreated()) {
            throw failureOf(handoff);
        }
        AgentExecution ended = executions.findById(handoff.execution().id()).orElseThrow();
        return new SkillDelegationRun(ended.id(), ended.status(), outputs.open(ended));
    }


    /** 화면 스킬 목록에 보이고 켜졌으며 커맨드로 부를 수 있는 이름이다. {@code skills} toolset 이 꺼졌으면 비어 있다. web 의 {@code commandSkillNames} 와 같다. */
    private static List<String> listedNames(SkillList list) {
        if (!list.skillsToolsetEnabled()) {
            return List.of();
        }
        return list.skills().stream()
            .filter(SkillListItem::enabled)
            .filter(item -> item.source() != SkillSource.HERMES)
            .map(SkillListItem::name)
            .filter(name -> SkillCommand.parse("/" + name).map(command -> command.name().equals(name)).orElse(false))
            .sorted()
            .toList();
    }


    /** 요청자가 맡길 수 있는 에이전트다. 쓸 수 있고 켜진 에이전트에서 기본 비서를 뺀다. */
    private List<Agent> delegable(CurrentUser user) {
        Optional<Long> primary = primaryAgents.of(user).map(Agent::id);
        return agents.readableBy(user).stream().filter(agent -> primary.filter(agent.id()::equals).isEmpty()).toList();
    }


    private void requireSkill(Agent agent, DelegatedSkillCommand command) {
        if (!skillCommands.enabledNames(agent).contains(command.command().name())) {
            throw new ApiException(ErrorCode.SKILL_COMMAND_UNKNOWN, "this agent has no enabled skill with that name");
        }
    }


    /** 실행 줄에 질문을 잇는다. 그래야 지난 {@code @} 맡김처럼 질문 아래 맡김 줄이 그려진다. 예약이면 사람이 없는 실행으로도 적는다. */
    private Consumer<AgentExecution> linkQuestion(SkillDelegationQuestion question, boolean unattended) {
        return execution -> {
            executionQuestions.link(execution.id(), question.messageId());
            if (unattended) {
                executionMarks.markUnattended(execution.id());
            }
        };
    }


    /** 맡기기마다 새 키다. 같은 요청을 다시 받는 일이 없어 무작위 값으로 만든다. */
    private static DelegationKey newKey(Agent agent) {
        String nonce = UUID.randomUUID().toString();
        return DelegationKey.of(agent.hermesProfile(), "skill-delegation", nonce, nonce);
    }


    private static ApiException failureOf(Handoff handoff) {
        if (handoff.failure() instanceof ApiException api) {
            return api;
        }
        return new ApiException(ErrorCode.HERMES_UNAVAILABLE, "the delegation could not start");
    }
}
