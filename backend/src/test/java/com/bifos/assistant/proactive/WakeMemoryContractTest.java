package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentMemoryCollection;
import com.bifos.assistant.agent.infra.AgentMemoryCollectionRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 매일 깨우기가 기억에서 꺼내는 「최근 일」 절의 권한 계약이다(ADR-20261101 / proactive-suggestion). 새 읽기 경로의 첫 계약이다.
 *
 * <p>깨우기는 {@code ProactiveCheckService.startScheduled} 를 직접 부르고, 대역 Hermes 가 받은 입력을 읽는다. 계약은
 * {@code docs/features/proactive.md} 의 「기억에서 꺼내는 것」 이 갖는다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
class WakeMemoryContractTest extends ProactiveLoopTestSupport {

    private static final String GRANTED = "core";
    private static final String NOT_GRANTED = "health";

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryLinkRepository links;

    @Autowired
    AgentMemoryCollectionRepository grants;

    private final List<Long> createdAgents = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();


    @AfterEach
    void removeMemory() {
        for (Long userId : createdUsers) {
            jdbc.update(
                "DELETE FROM memory_link WHERE from_memory_id IN (SELECT id FROM memory WHERE owner_user_id = ?)"
                    + " OR to_memory_id IN (SELECT id FROM memory WHERE owner_user_id = ?)",
                userId,
                userId);
            jdbc.update("DELETE FROM memory_revision WHERE memory_id IN (SELECT id FROM memory WHERE owner_user_id = ?)", userId);
            jdbc.update("DELETE FROM memory_embedding WHERE memory_id IN (SELECT id FROM memory WHERE owner_user_id = ?)", userId);
            jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", userId);
        }
        createdAgents.forEach(agentId -> jdbc.update("DELETE FROM agent_memory_collection WHERE agent_id = ?", agentId));
        createdAgents.clear();
        createdUsers.clear();
    }


    @Test
    @DisplayName("깨우기 입력은 에이전트가 받는 기억 가운데 최근에 바뀐 항목과 그 한 단계 이웃의 제목만 싣고, 받지 않는 collection 과 남의 기억과 대체된 기억은 연결이 있어도 싣지 않는다")
    void wakeInputCarriesOnlyGrantedRecentMemoryAndItsLinks() throws InterruptedException {
        CurrentUser user = user();
        CurrentUser stranger = user();
        Agent agent = agent(user);

        clock.set(BASE.minus(Duration.ofDays(30)));
        Memory goal = create(user, "목표표식 이번 분기 이직 준비", GRANTED);
        Memory replaced = create(user, "대체표식 옛 희망 연봉", GRANTED);
        clock.set(BASE.minus(Duration.ofDays(1)));
        Memory recent = create(user, "최근표식 예시 회사 1차 면접 통과", GRANTED);
        Memory hidden = create(user, "숨김표식 병원 예약", NOT_GRANTED);
        Memory replacement = create(user, "새값표식 새 희망 연봉", GRANTED);
        Memory others = create(stranger, "남의표식 다른 사람 메모", GRANTED);
        links.save(MemoryLink.related(recent.id(), goal.id(), "이 목표의 일부", MemoryLinkOrigin.HUMAN, null, null));
        links.save(MemoryLink.related(recent.id(), hidden.id(), null, MemoryLinkOrigin.HUMAN, null, null));
        links.save(MemoryLink.related(recent.id(), others.id(), null, MemoryLinkOrigin.HUMAN, null, null));
        links.save(MemoryLink.related(recent.id(), replaced.id(), null, MemoryLinkOrigin.HUMAN, null, null));
        MemoryLink supersede = MemoryLink.proposeSupersede(replacement.id(), replaced.id(), MemoryLinkOrigin.HUMAN, null);
        supersede.accept();
        links.save(supersede);
        clock.set(BASE);

        wake(user, agent, nothingNewOutput());

        String input = onlyInput();
        assertThat(input)
            .contains("기억에서 꺼낸 최근 일")
            .contains("[" + recent.id() + "] 최근표식")
            .contains("[" + replacement.id() + "] 새값표식")
            .as("오래된 목표는 바뀐 항목이 아니지만 이어진 항목으로 label 과 함께 실린다")
            .contains("[" + goal.id() + "] 목표표식 이번 분기 이직 준비 · [" + recent.id() + "] 에 이어짐 · 이 목표의 일부")
            .doesNotContain("숨김표식")
            .doesNotContain("남의표식")
            .doesNotContain("대체표식");
        assertThat(input).as("본문은 싣지 않는다").doesNotContain("본문표식");
    }


    @Test
    @DisplayName("에이전트가 받는 기억이 없으면 깨우기 입력은 바뀐 기억이 없다고만 적는다")
    void wakeInputSaysNothingWhenAgentReceivesNoMemory() throws InterruptedException {
        CurrentUser user = user();
        Agent agent = agent(user);
        grants.deleteAll(grants.findByIdAgentId(agent.id()));
        clock.set(BASE.minus(Duration.ofDays(1)));
        create(user, "최근표식 받지 않는 기억", GRANTED);
        clock.set(BASE);

        wake(user, agent, nothingNewOutput());

        assertThat(onlyInput())
            .contains("기억에서 꺼낸 최근 일\n지난 살펴보기 뒤에 바뀐 기억이 없다.")
            .doesNotContain("최근표식");
    }


    @Test
    @DisplayName("두 번째 깨우기는 앞 깨우기가 시작한 뒤에 바뀐 기억만 바뀐 항목으로 싣는다")
    void secondWakeCarriesOnlyMemoryChangedAfterPreviousWake() throws InterruptedException {
        CurrentUser user = user();
        Agent agent = agent(user);
        clock.set(BASE.minus(Duration.ofDays(1)));
        create(user, "앞표식 앞 깨우기 전에 바뀐 기억", GRANTED);
        clock.set(BASE);
        wake(user, agent, nothingNewOutput());

        clock.set(BASE.plus(Duration.ofHours(12)));
        Memory later = create(user, "뒤표식 앞 깨우기 뒤에 바뀐 기억", GRANTED);
        clock.set(BASE.plus(Duration.ofDays(1)));
        wake(user, agent, nothingNewOutput());

        List<HermesRunCommand> received = ((StubHermesRunsClient)hermes).received();
        assertThat(received).hasSize(2);
        assertThat(received.get(0).input()).contains("앞표식");
        assertThat(received.get(1).input())
            .contains("[" + later.id() + "] 뒤표식")
            .doesNotContain("앞표식");
    }


    @Test
    @DisplayName("실패한 깨우기는 경계를 옮기지 않아 그 전에 바뀐 기억을 다음 깨우기가 싣고, 앞 깨우기가 도는 동안 바뀐 기억도 싣는다")
    void failedWakeDoesNotMoveWindow() throws InterruptedException {
        CurrentUser user = user();
        Agent agent = agent(user);
        clock.set(BASE.minus(Duration.ofDays(1)));
        create(user, "앞표식 실패 전에 바뀐 기억", GRANTED);
        clock.set(BASE);
        StubHermesRunsClient stub = (StubHermesRunsClient)hermes;
        stub.willFail(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "합성 장애"));
        checkService.startScheduled(user, agent.code(), saved -> {
        });
        awaitIdle();

        clock.set(BASE.plus(Duration.ofDays(1)));
        wake(user, agent, nothingNewOutput());

        List<HermesRunCommand> received = stub.received();
        assertThat(received.getLast().input()).contains("앞표식");
    }


    @Test
    @DisplayName("항상 싣는 기억은 본문이 이미 문맥에 있다고 표시하고 색인 기억은 표시하지 않는다")
    void alwaysMemoryIsMarkedInContext() throws InterruptedException {
        CurrentUser user = user();
        Agent agent = agent(user);
        clock.set(BASE.minus(Duration.ofDays(1)));
        Memory always = memories.create(
            user, MemoryScope.USER, "항상표식 매일 아침 운동", "본문표식", GRANTED, MemoryRetrieval.ALWAYS, MemorySensitivity.NORMAL);
        Memory indexed = create(user, "색인표식 주말 계획", GRANTED);
        clock.set(BASE);

        wake(user, agent, nothingNewOutput());

        assertThat(onlyInput())
            .contains("[" + always.id() + "] 항상표식 매일 아침 운동 · 바뀜 2026-11-01 · 본문은 문맥에 있음")
            .contains("[" + indexed.id() + "] 색인표식 주말 계획 · 바뀜 2026-11-01\n");
    }


    private CurrentUser user() {
        CurrentUser user = newUser();
        createdUsers.add(user.id());
        return user;
    }


    /** {@link #GRANTED} 만 민감 항목 없이 받는 에이전트다. */
    private Agent agent(CurrentUser user) {
        Agent agent = newAgent(user);
        createdAgents.add(agent.id());
        grants.save(AgentMemoryCollection.of(agent.id(), GRANTED, false));
        return agent;
    }


    private Memory create(CurrentUser user, String title, String collection) {
        return memories.create(
            user,
            MemoryScope.USER,
            title,
            "본문표식 " + title,
            collection,
            MemoryRetrieval.SEARCH,
            MemorySensitivity.NORMAL);
    }


    private String onlyInput() {
        List<HermesRunCommand> received = ((StubHermesRunsClient)hermes).received();
        assertThat(received).as("깨우기 한 번이 Hermes 에 보낸 실행").hasSize(1);
        return received.getFirst().input();
    }
}
