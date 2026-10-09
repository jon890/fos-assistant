package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.SkillInputRules.bundleOf;
import static com.bifos.assistant.skill.application.SkillInputRules.requireFiles;
import static com.bifos.assistant.skill.application.SkillInputRules.requireNoStoredSecretRequests;
import static com.bifos.assistant.skill.application.SkillInputRules.requireSkillMd;
import static com.bifos.assistant.skill.application.SkillInputRules.utf8Bytes;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRequestRejected;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesSkillName;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.PreviousSkill;
import com.bifos.assistant.skill.infra.PreviousSkillStore;
import com.bifos.assistant.skill.infra.SkillFilePaths;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.skill.infra.SkillPublisher;
import com.bifos.assistant.skill.infra.SkillStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * 에이전트에 올린 스킬의 권한 판정과 저장 순서를 안다.
 *
 * <p>본문은 공유 디렉터리가 갖고 데이터베이스에는 두지 않는다. 저장할 때마다 그 profile 의 올린 스킬
 * 전체를 새 버전 디렉터리에 쓰고 Hermes 에 게시한다. 근거는 ADR-034 에 있다.
 *
 * <p>같은 에이전트의 저장은 기다리는 행 잠금으로 한 번에 하나씩 돈다. 잠금은 트랜잭션이 끝날 때 풀리므로
 * {@link #save} 와 {@link #delete} 는 잠금부터 표식 쓰기와 옛 버전 정리까지 한 트랜잭션이다. 그동안
 * 같은 에이전트의 도구와 기본 모델 변경은 기다리지 않는 잠금이라 {@code AGENT_BUSY} 로 바로 거절되고,
 * 주인의 공개 범위 변경과 에이전트 지우기는 잠금이 풀릴 때까지 기다린다.
 * 관리자 수정도 기다리지 않는 잠금이라 {@code AGENT_BUSY} 로 바로 거절된다.
 *
 * <p>저장, 지우기, 켜고 끄기가 Hermes 에 반영되면 {@link SkillsChanged} 를 낸다. 커맨드가 부를 수 있는 이름의
 * 캐시({@link SkillCommandCatalog})가 그것을 받아 비운다. catalog 를 직접 받지 않는 것은 catalog 가 이
 * 서비스를 받기 때문이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillService {

    // 아래 파일 세 한도는 화면(web/src/components/agent/skill-editor.tsx)이, 설명 두 한도는 web/src/lib/skill.ts 가
    // 같은 값으로 저장 전에 검사한다. 바꾸면 함께 고친다.

    /** 참고 파일 수의 상한. {@code SKILL.md} 는 세지 않는다. */
    public static final int MAX_FILES = 20;

    /** 파일 하나의 글자 수 상한. Hermes 의 {@code SKILL.md} 상한과 같다. */
    public static final int MAX_CHARS_PER_FILE = 100_000;

    /** 스킬 하나의 UTF-8 바이트 합계 상한. 1 MiB 다. */
    public static final long MAX_TOTAL_BYTES = 1_048_576L;

    /**
     * 새 스킬 설명의 글자 수 상한. 이미 올린 스킬을 고칠 때는 보지 않는다. Hermes v0.21.5 의
     * {@code SKILL_PROMPT_DESC_LIMIT} 와 같다. Hermes 를 올리며 바뀌면 함께 고친다.
     */
    public static final int MAX_NEW_DESCRIPTION_CHARS = 60;

    /** 설명의 글자 수 상한. 저장할 때마다 본다. Hermes v0.21.5 가 스킬을 쓸 때마다 보는 상한과 같다. */
    public static final int MAX_DESCRIPTION_CHARS = 1024;

    private final AgentService agents;
    private final AgentConnectorBindings connectorBindings;
    private final SkillStore store;
    private final PreviousSkillStore previousStore;
    private final SkillPublisher publisher;
    private final SkillUsageQuery usage;
    private final ApplicationEventPublisher events;
    private final SkillProperties properties;
    private final NewSkillRules newSkills;

    /**
     * 일반 화면에 보일 올린 스킬 목록이다. 관리자 역할도 기본·커넥터 스킬은 받지 않는다.
     *
     * <p>Hermes 목록에 올린 스킬 이름을 대조해 출처를 붙인다. 올린 스킬 이름은 지금 버전과, 게시가 timeout
     * 으로 끝나 표식 없이 남은 더 새 버전의 이름이다. Hermes 가 그 버전을 이미 반영했을 수 있기 때문이다.
     * 지금 버전에 있는데 Hermes 목록에 없는 스킬도 올린 것으로 넣는다. 게시 직후 색인 전이거나 Hermes 가
     * 건너뛴 스킬을 화면에서 지울 수 있어야 하기 때문이다.
     *
     * <p>호출 합계는 편집자에게만 채운다. 그 에이전트의 실행 전체에서 센 것이라 누가 불렀는지는 담지 않는다.
     * 호출이 없는 스킬은 0 이다.
     */
    public SkillList list(CurrentUser user, String code) {
        Agent agent = agents.requireReadable(user, code);
        boolean editable = agents.isEditableBy(user, agent);
        Map<String, SkillUsageSummary> usages = editable ? usage.byAgent(agent.id()) : Map.of();
        SkillList list = assemble(agent, editable, usages);
        return new SkillList(
                list.skills().stream()
                        .filter(item -> item.source() == SkillSource.UPLOADED)
                        .toList(),
                list.editable(),
                list.skillsToolsetEnabled(),
                list.uploadLimit());
    }

    /** 관리자 영역에서만 Hermes 번들과 커넥터 스킬을 함께 읽는다. */
    public SkillList adminList(CurrentUser user, String code) {
        if (!user.isAdmin()) {
            throw forbidden();
        }
        Agent agent = agents.requireReadable(user, code);
        return assemble(agent, true, usage.byAgent(agent.id()));
    }

    /**
     * 스킬 커맨드가 이름을 확인할 전체 목록이다. 화면 목록의 숨김과 별개로 Hermes 기본 스킬도 조립하고 호출 합계를
     * 읽지 않는다.
     *
     * <p>누가 그 에이전트를 쓸 수 있는지는 부르는 쪽이 이미 판정했다. 대화 turn 은 새 대화면 시작할 수 있는
     * 에이전트인지, 이어 쓰는 대화면 그 대화의 주인인지를 본 뒤에 부른다. 물어본 사람이 없으므로 {@code editable}
     * 은 {@code false} 이고 항목의 {@code usage} 는 {@code null} 이다.
     */
    public SkillList commandList(Agent agent) {
        return assemble(agent, false, Map.of());
    }

    /** Hermes 목록과 올린 스킬 이름을 합친다. {@link #list} 와 {@link #commandList} 가 같은 조립을 쓴다. */
    private SkillList assemble(Agent agent, boolean editable, Map<String, SkillUsageSummary> usages) {
        String profile = agent.hermesProfile();
        Map<String, SkillBundle> uploaded = store.readCurrent(profile);
        Set<String> uploadedNames = NewSkillRules.uploadedNames(uploaded, store.readPending(profile));
        Map<String, SkillListItem> items = new TreeMap<>();
        for (HermesSkill skill : publisher.list(profile)) {
            SkillSource source = uploadedNames.contains(skill.name()) ? SkillSource.UPLOADED : SkillSource.HERMES;
            items.put(
                    skill.name(),
                    new SkillListItem(
                            skill.name(),
                            skill.description(),
                            source,
                            skill.enabled(),
                            usageOf(editable, usages, skill.name())));
        }
        for (SkillBundle bundle : uploaded.values()) {
            items.putIfAbsent(
                    bundle.name(),
                    new SkillListItem(
                            bundle.name(),
                            descriptionOf(bundle.skillMd()),
                            SkillSource.UPLOADED,
                            true,
                            usageOf(editable, usages, bundle.name())));
        }
        return new SkillList(
                List.copyOf(items.values()), editable, publisher.skillsToolsetEnabled(agent), properties.maxPerAgent());
    }

    private static SkillUsageSummary usageOf(boolean editable, Map<String, SkillUsageSummary> usages, String name) {
        if (!editable) {
            return null;
        }
        return usages.getOrDefault(name, new SkillUsageSummary(0, null));
    }

    /**
     * 올린 스킬 하나의 원문이다. 편집자만 본다. 없으면 {@link ErrorCode#SKILL_NOT_FOUND} 다.
     *
     * <p>지금 버전에 없으면 표식 없이 남은 더 새 버전의 것을 준다. timeout 뒤 Hermes 목록에만 뜬 스킬을
     * 편집 화면에서 열어 다시 저장할 수 있어야 하기 때문이다.
     */
    public SkillDetail read(CurrentUser user, String code, String name) {
        Agent agent = requireEditable(user, code);
        String profile = agent.hermesProfile();
        SkillBundle bundle = uploadedBundle(profile, store.readCurrent(profile), name);
        if (bundle == null) {
            throw notFound();
        }
        return detailOf(bundle, previousSavedAt(profile, name));
    }

    /**
     * 스킬 하나를 통째로 바꾼다. 없으면 만든다.
     *
     * <p>게시가 4xx 로 거절되면 새 디렉터리를 지우고 원래 오류를 올린다. timeout 과 5xx 는 Hermes 가 이미
     * 반영했을 수 있어 디렉터리를 표식 없이 두고 원래 오류를 올린다. 어느 쪽이든 지금 버전은 그대로라
     * 이 저장의 변경은 반영되지 않는다. 그 뒤 같은 이름으로 다시 저장하면 표식 없는 버전의 이름을 올린
     * 스킬로 보고 받는다. Hermes 목록에 그 이름이 먼저 떠 있어도 {@link ErrorCode#SKILL_NAME_TAKEN} 이 아니다.
     *
     * <p>새 스킬일 때만 두 가지를 더 본다. 설명이 {@link #MAX_NEW_DESCRIPTION_CHARS} 자를 넘지 않는지와, 올린
     * 스킬 수가 {@code assistant.skill.max-per-agent} 에 닿지 않았는지다. Hermes 색인이 설명을 자르지 않고
     * 커지지 않게 하려는 것이다(ADR-034). 이미 올린 스킬은 Hermes 처럼 두 검사 없이 고칠 수 있다. 개수는 에이전트
     * 행 잠금을 잡은 뒤 읽은 버전으로 세야 동시에 온 두 생성이 함께 통과하지 않는다.
     *
     * <p>저장하는 스킬에 {@code scripts/} 가 있으면 실행 공간이 있는 에이전트에만 받는다. 이미 있던 스킬이면 게시에 성공한
     * 뒤 바뀌기 전 것을 이전 버전으로 남긴다(ADR-20261009-skill-package).
     *
     * @param files 참고 파일. {@code content} 가 {@code null} 인 파일은 지금 버전의 같은 경로 내용을 쓴다
     */
    @Transactional
    public SkillDetail save(CurrentUser user, String code, String name, String skillMd, List<SkillFileInput> files) {
        Agent agent = requireEditableLocked(user, code);
        String profile = agent.hermesProfile();
        SkillFilePaths.requireSkillName(name);
        SkillFrontmatter frontmatter = requireSkillMd(name, skillMd);
        List<SkillFileInput> inputs = requireFiles(files);
        Map<String, SkillBundle> current = store.readCurrent(profile);
        Map<String, SkillBundle> pending = store.readPending(profile);
        SkillBundle uploaded = uploadedBundle(current, pending, name);
        if (uploaded == null) {
            newSkills.requireCreatable(profile, name, frontmatter, current, pending);
        }
        SkillBundle bundle = bundleOf(name, skillMd, inputs, uploaded);
        return saveBundle(user, agent, current, bundle, uploaded);
    }

    /** 편집자인지만 본다. 잠금은 잡지 않는다. 스킬 묶음의 미리보기와 올리기가 zip 을 풀기 전에 부른다. */
    public Agent requireManageable(CurrentUser user, String code) {
        return requireEditable(user, code);
    }

    /** 검사를 마친 묶음을 행 잠금 안에서 지금 스킬의 지문과 {@code baseDigest} 가 같을 때만 저장한다. */
    @Transactional
    public SkillDetail saveUploaded(CurrentUser user, String code, SkillBundle bundle, String baseDigest) {
        Agent agent = requireEditableLocked(user, code);
        String profile = agent.hermesProfile();
        Map<String, SkillBundle> current = store.readCurrent(profile);
        Map<String, SkillBundle> pending = store.readPending(profile);
        SkillBundle uploaded = uploadedBundle(current, pending, bundle.name());
        String base = baseDigest == null || baseDigest.isBlank() ? null : baseDigest;
        if (!Objects.equals(uploaded == null ? null : uploaded.digest(), base)) {
            throw new ApiException(ErrorCode.SKILL_CHANGED, "the skill changed after the preview");
        }
        if (uploaded == null) {
            newSkills.requireCreatable(
                    profile, bundle.name(), SkillFrontmatter.parse(bundle.skillMd()), current, pending);
        }
        return saveBundle(user, agent, current, bundle, uploaded);
    }

    /**
     * 그 스킬의 이전 버전과 지금 버전을 맞바꿔 저장한다. 편집자만 한다.
     *
     * <p>지금 스킬이나 이전 버전이 없으면 {@link ErrorCode#SKILL_NOT_FOUND} 다. 이미 있는 스킬이라 새 스킬의 설명 60자와
     * 개수 한도, Hermes 이름 검사는 하지 않는다. 비밀 요청 칸과 {@code scripts/} 조건은 저장과 같이 본다. 성공하면
     * 바뀌기 전 스킬이 새 이전 버전이다.
     */
    @Transactional
    public SkillDetail restorePrevious(CurrentUser user, String code, String name) {
        Agent agent = requireEditableLocked(user, code);
        String profile = agent.hermesProfile();
        SkillFilePaths.requireSkillName(name);
        Map<String, SkillBundle> current = store.readCurrent(profile);
        SkillBundle uploaded = uploadedBundle(current, store.readPending(profile), name);
        if (uploaded == null) {
            throw notFound();
        }
        SkillBundle previous = previousStore
                .readPrevious(profile, name)
                .map(PreviousSkill::bundle)
                .orElseThrow(SkillService::notFound);
        requireSkillMd(name, previous.skillMd());
        return saveBundle(user, agent, current, previous, uploaded);
    }

    /**
     * 검사를 마친 스킬 하나를 지금 버전에 실어 게시한다. 저장과 되돌리기가 같은 흐름을 쓴다.
     *
     * <p>{@code scripts/} 판정은 저장하는 그 스킬에만 한다. 함께 실리는 다른 스킬은 보지 않는다. 셸을 끈 에이전트도 다른
     * 스킬을 고칠 수 있어야 하기 때문이다. 이전 버전 쓰기는 게시가 성공한 뒤라 실패해도 저장을 실패로 바꾸지 않는다.
     * 새 스킬이면 같은 이름으로 남은 이전 버전을 지운다. 지운 스킬의 이전 버전이 새 스킬의 것으로 보이지 않게 한다.
     *
     * @param replaced 저장 전에 있던 같은 이름의 스킬. 새 스킬이면 {@code null} 이다
     */
    private SkillDetail saveBundle(
            CurrentUser user, Agent agent, Map<String, SkillBundle> current, SkillBundle bundle, SkillBundle replaced) {
        String profile = agent.hermesProfile();
        Map<String, SkillBundle> next = new LinkedHashMap<>(current);
        next.put(bundle.name(), bundle);
        requireNoStoredSecretRequests(next, bundle.name());
        boolean withScripts = bundle.files().stream().anyMatch(file -> SkillFilePaths.isScript(file.path()));
        if (withScripts && !publisher.terminalEnabled(agent)) {
            throw new ApiException(
                    ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX, "this agent has no sandbox shell to run skill scripts");
        }
        publishVersion(user, agent, next, withScripts);
        events.publishEvent(new SkillsChanged(agent.id()));
        if (replaced != null) {
            writePreviousQuietly(profile, replaced);
        } else {
            deletePreviousQuietly(profile, bundle.name());
        }
        return detailOf(bundle, previousSavedAt(profile, bundle.name()));
    }

    private void writePreviousQuietly(String profile, SkillBundle replaced) {
        try {
            previousStore.writePrevious(profile, replaced);
        } catch (RuntimeException ex) {
            log.warn("바뀌기 전 스킬을 이전 버전으로 남기지 못했다 profile={} skill={}", profile, replaced.name(), ex);
        }
    }

    /** 게시가 끝난 뒤 이전 버전을 지운다. 실패해도 저장과 지우기를 실패로 바꾸지 않고 경고 로그만 남긴다. */
    private void deletePreviousQuietly(String profile, String name) {
        try {
            previousStore.deletePrevious(profile, name);
        } catch (RuntimeException ex) {
            log.warn("스킬의 이전 버전을 지우지 못했다 profile={} skill={}", profile, name, ex);
        }
    }

    /**
     * 이전 버전을 남긴 시각이다. 남긴 시각 파일만 읽는다. 없거나 읽지 못하면 {@code null} 이고 읽기와 저장을 막지
     * 않는다.
     */
    private Instant previousSavedAt(String profile, String name) {
        try {
            return previousStore.previousSavedAt(profile, name).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("스킬의 이전 버전을 읽지 못했다 profile={} skill={}", profile, name, ex);
            return null;
        }
    }

    /**
     * 올린 스킬을 지운다. 그 스킬을 뺀 새 버전을 저장과 같은 순서로 게시한다.
     *
     * <p>남는 스킬이 없으면 새 버전을 쓰지 않고 빈 {@code external_dirs} 를 게시한 뒤 그 profile 의 버전
     * 디렉터리와 이전 버전을 모두 지운다. 게시가 성공한 뒤라 Hermes 가 가리키는 디렉터리가 없다. profile 디렉터리는
     * 실행 공간이 붙이고 있으므로 남긴다. 게시가 끝나면 그 스킬의 이전 버전도 지운다. 이전 버전 지우기가 실패해도 지우기는
     * 성공으로 두고 {@link SkillsChanged} 를 낸다. Hermes 는 이미 그 스킬을 내려놓았다.
     *
     * <p>표식 없는 더 새 버전에만 있는 스킬도 지운다. 지금 버전을 다시 게시하면 Hermes 가 그 버전에서
     * 벗어난다.
     */
    @Transactional
    public void delete(CurrentUser user, String code, String name) {
        Agent agent = requireEditableLocked(user, code);
        String profile = agent.hermesProfile();
        Map<String, SkillBundle> current = store.readCurrent(profile);
        if (uploadedBundle(profile, current, name) == null) {
            throw notFound();
        }
        Map<String, SkillBundle> remaining = new LinkedHashMap<>(current);
        remaining.remove(name);
        if (remaining.isEmpty()) {
            publisher.publish(user, agent, List.of(), connectorBindings.connectorServers(agent.id()));
            store.clearVersions(profile);
        } else {
            publishVersion(user, agent, remaining, false);
        }
        deletePreviousQuietly(profile, name);
        events.publishEvent(new SkillsChanged(agent.id()));
    }

    /**
     * 올린 스킬을 켜고 끈다. 편집자만 하고 기본·커넥터 스킬은 없는 스킬과 같은 오류를 준다.
     */
    public void toggle(CurrentUser user, String code, String name, boolean enabled) {
        Agent agent = requireEditable(user, code);
        if (uploadedBundle(agent.hermesProfile(), store.readCurrent(agent.hermesProfile()), name) == null) {
            throw notFound();
        }
        toggle(agent, name, enabled);
    }

    /** 기본 스킬의 켜고 끄기는 관리자 경로에서만 받는다. 설정은 그 profile 의 모든 실행에 적용된다. */
    public void adminToggle(CurrentUser user, String code, String name, boolean enabled) {
        if (!user.isAdmin()) {
            throw forbidden();
        }
        toggle(requireEditable(user, code), name, enabled);
    }

    private void toggle(Agent agent, String name, boolean enabled) {
        if (!HermesSkillName.isValid(name)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a skill name must follow the Hermes skill name rule");
        }
        publisher.toggle(agent.hermesProfile(), name, enabled);
        events.publishEvent(new SkillsChanged(agent.id()));
    }

    /**
     * 지금 버전의 그 스킬, 없으면 표식 없이 남은 더 새 버전의 그 스킬이다. 둘 다 없으면 {@code null} 이다.
     * 더 새 버전은 지금 버전에 없을 때만 읽는다.
     */
    private SkillBundle uploadedBundle(String profile, Map<String, SkillBundle> current, String name) {
        SkillBundle bundle = current.get(name);
        return bundle != null ? bundle : store.readPending(profile).get(name);
    }

    /** 이미 읽은 더 새 버전으로 찾는다. 같은 트랜잭션에서 그 버전을 다시 읽지 않으려는 저장이 쓴다. */
    private static SkillBundle uploadedBundle(
            Map<String, SkillBundle> current, Map<String, SkillBundle> pending, String name) {
        SkillBundle bundle = current.get(name);
        return bundle != null ? bundle : pending.get(name);
    }

    /**
     * 새 버전을 쓰고 게시하고 표식을 쓰고 옛 버전을 정리한다. 저장과 지우기가 같은 순서를 쓴다.
     *
     * <p>{@code withScripts} 면 실행 공간이 있어야 하는 게시로 보낸다. 대시보드가 실행 공간이 없다고 거절하면
     * {@link ErrorCode#SKILL_SCRIPTS_NEED_SANDBOX} 로 바꾼다. 첨부 디렉터리를 준비하지 못한 거절도 같은 코드로 오지만 셸
     * 유무가 원인이 아니어서 그대로 올린다. 그 예외의 cause 는 대시보드 응답이 아니다.
     */
    private void publishVersion(CurrentUser user, Agent agent, Map<String, SkillBundle> skills, boolean withScripts) {
        String profile = agent.hermesProfile();
        String version = store.writeVersion(profile, skills);
        try {
            List<String> externalDirs = List.of(store.agentPath(profile, version));
            Set<String> connectorServers = connectorBindings.connectorServers(agent.id());
            if (withScripts) {
                publisher.publishWithScripts(user, agent, externalDirs, connectorServers);
            } else {
                publisher.publish(user, agent, externalDirs, connectorServers);
            }
        } catch (HermesRequestRejected rejected) {
            discardQuietly(profile, version);
            if (withScripts
                    && rejected.code() == ErrorCode.AGENT_SANDBOX_UNAVAILABLE
                    && rejected.getCause() instanceof RestClientResponseException) {
                throw new ApiException(
                        ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX,
                        "this agent's profile has no sandbox to run skill scripts",
                        rejected);
            }
            throw rejected;
        }
        store.markPublished(profile, version);
        store.prune(profile, version);
    }

    /** 거절된 버전을 지운다. 지우기가 실패해도 원래 오류를 가리지 않고 로그로만 남긴다. */
    private void discardQuietly(String profile, String version) {
        try {
            store.discard(profile, version);
        } catch (RuntimeException ex) {
            log.warn("게시가 거절된 스킬 버전을 지우지 못했다 profile={} version={}", profile, version, ex);
        }
    }

    private Agent requireEditable(CurrentUser user, String code) {
        Agent agent = agents.requireReadable(user, code);
        if (!agents.isEditableBy(user, agent)) {
            throw forbidden();
        }
        return agent;
    }

    /** 편집자인지 본 뒤 그 행을 기다리는 잠금으로 다시 읽고, 잠금을 잡은 뒤의 권한을 한 번 더 본다. */
    private Agent requireEditableLocked(CurrentUser user, String code) {
        Agent locked = agents.lockForUpdate(user, requireEditable(user, code));
        if (!agents.isEditableBy(user, locked)) {
            throw forbidden();
        }
        return locked;
    }

    private static SkillDetail detailOf(SkillBundle bundle, Instant previousSavedAt) {
        List<SkillFileInfo> files = bundle.files().stream()
                .map(file -> new SkillFileInfo(file.path(), utf8Bytes(file.content()), file.content()))
                .toList();
        return new SkillDetail(
                bundle.name(), descriptionOf(bundle.skillMd()), bundle.skillMd(), files, previousSavedAt);
    }

    /** 저장된 원문의 설명이다. 저장할 때 검사했으므로 읽지 못하면 비워 두고 읽기를 막지 않는다. */
    private static String descriptionOf(String skillMd) {
        try {
            return SkillFrontmatter.parse(skillMd).description();
        } catch (ApiException ex) {
            return "";
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.SKILL_NOT_FOUND, "no such uploaded skill");
    }

    private static ApiException forbidden() {
        return new ApiException(
                ErrorCode.FORBIDDEN, "only the owner of this agent or the group admin can manage its skills");
    }
}
