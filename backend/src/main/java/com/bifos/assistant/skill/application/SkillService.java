package com.bifos.assistant.skill.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRequestRejected;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.infra.SkillPublisher;
import com.bifos.assistant.skill.infra.SkillStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트에 올린 스킬의 권한 판정과 저장 순서를 안다.
 *
 * <p>본문은 공유 디렉터리가 갖고 데이터베이스에는 두지 않는다. 저장할 때마다 그 profile 의 올린 스킬
 * 전체를 새 버전 디렉터리에 쓰고 Hermes 에 게시한다. 근거는 ADR-034 에 있다.
 *
 * <p>같은 에이전트의 저장은 기다리는 행 잠금으로 한 번에 하나씩 돈다. 잠금은 트랜잭션이 끝날 때 풀리므로
 * {@link #save} 와 {@link #delete} 는 잠금부터 표식 쓰기와 옛 버전 정리까지 한 트랜잭션이다. 그동안
 * 같은 에이전트의 도구와 공개 범위 변경은 {@code AGENT_BUSY} 로 거절된다.
 *
 * <p>저장, 지우기, 켜고 끄기가 Hermes 에 반영되면 {@link SkillsChanged} 를 낸다. 커맨드가 부를 수 있는 이름의
 * 캐시({@link SkillCommandCatalog})가 그것을 받아 비운다. catalog 를 직접 받지 않는 것은 catalog 가 이
 * 서비스를 받기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    // 아래 세 한도는 화면(web/src/components/agent/skill-editor.tsx)이 같은 값으로 저장 전에 검사한다.
    // 바꾸면 두 곳을 함께 고친다.

    /** 참고 파일 수의 상한. {@code SKILL.md} 는 세지 않는다. */
    public static final int MAX_FILES = 20;

    /** 파일 하나의 글자 수 상한. Hermes 의 {@code SKILL.md} 상한과 같다. */
    public static final int MAX_CHARS_PER_FILE = 100_000;

    /** 스킬 하나의 UTF-8 바이트 합계 상한. 1 MiB 다. */
    public static final long MAX_TOTAL_BYTES = 1_048_576L;

    /**
     * Hermes 가 가진 스킬까지 포함한 이름 형식이다. 켜고 끄기와 호출 이력({@link SkillUseRecorder})이 쓴다. Hermes 는 소문자, 숫자, 점, 밑줄,
     * 붙임표로 64자까지 받는다. 첫 글자를 영문 소문자나 숫자로 묶어 {@code .} 과 {@code ..} 같은 이름을
     * 막는다. 화면(web/src/lib/skill.ts)의 같은 규칙과 함께 고친다.
     */
    static final Pattern HERMES_SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final AgentService agents;
    private final SkillStore store;
    private final SkillPublisher publisher;
    private final SkillUsageQuery usage;
    private final ApplicationEventPublisher events;

    /**
     * 그 에이전트의 스킬 목록이다. 읽을 수 있는 사람이면 누구나 본다.
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
        String profile = agent.hermesProfile();
        Map<String, SkillBundle> uploaded = store.readCurrent(profile);
        Set<String> uploadedNames = uploadedNames(uploaded, store.readPending(profile));
        Map<String, SkillUsageSummary> usages = editable ? usage.byAgent(agent.id()) : Map.of();
        Map<String, SkillListItem> items = new TreeMap<>();
        for (HermesSkill skill : publisher.list(profile)) {
            SkillSource source = uploadedNames.contains(skill.name()) ? SkillSource.UPLOADED : SkillSource.HERMES;
            items.put(skill.name(), new SkillListItem(
                    skill.name(), skill.description(), source, skill.enabled(),
                    usageOf(editable, usages, skill.name())));
        }
        for (SkillBundle bundle : uploaded.values()) {
            items.putIfAbsent(bundle.name(), new SkillListItem(
                    bundle.name(), descriptionOf(bundle.skillMd()), SkillSource.UPLOADED, true,
                    usageOf(editable, usages, bundle.name())));
        }
        return new SkillList(List.copyOf(items.values()), editable, publisher.skillsToolsetEnabled(agent));
    }

    private static SkillUsageSummary usageOf(
            boolean editable, Map<String, SkillUsageSummary> usages, String name) {
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
        SkillBundle bundle = uploadedBundle(agent.hermesProfile(), store.readCurrent(agent.hermesProfile()), name);
        if (bundle == null) {
            throw notFound();
        }
        return detailOf(bundle);
    }

    /**
     * 스킬 하나를 통째로 바꾼다. 없으면 만든다.
     *
     * <p>게시가 4xx 로 거절되면 새 디렉터리를 지우고 원래 오류를 올린다. timeout 과 5xx 는 Hermes 가 이미
     * 반영했을 수 있어 디렉터리를 표식 없이 두고 원래 오류를 올린다. 어느 쪽이든 지금 버전은 그대로라
     * 이 저장의 변경은 반영되지 않는다. 그 뒤 같은 이름으로 다시 저장하면 표식 없는 버전의 이름을 올린
     * 스킬로 보고 받는다. Hermes 목록에 그 이름이 먼저 떠 있어도 {@link ErrorCode#SKILL_NAME_TAKEN} 이 아니다.
     *
     * @param files 참고 파일. {@code content} 가 {@code null} 인 파일은 지금 버전의 같은 경로 내용을 쓴다
     */
    @Transactional
    public SkillDetail save(
            CurrentUser user, String code, String name, String skillMd, List<SkillFileInput> files) {
        Agent agent = requireEditableLocked(user, code);
        String profile = agent.hermesProfile();
        SkillStore.requireSkillName(name);
        requireSkillMd(name, skillMd);
        List<SkillFileInput> inputs = requireFiles(files);
        Map<String, SkillBundle> current = store.readCurrent(profile);
        SkillBundle uploaded = uploadedBundle(profile, current, name);
        if (uploaded == null && publisher.list(profile).stream().anyMatch(s -> s.name().equals(name))) {
            throw new ApiException(ErrorCode.SKILL_NAME_TAKEN, "Hermes already has a skill with this name");
        }
        SkillBundle bundle = bundleOf(name, skillMd, inputs, uploaded);
        Map<String, SkillBundle> next = new LinkedHashMap<>(current);
        next.put(name, bundle);
        publishVersion(user, agent, next);
        events.publishEvent(new SkillsChanged(agent.id()));
        return detailOf(bundle);
    }

    /**
     * 올린 스킬을 지운다. 그 스킬을 뺀 새 버전을 저장과 같은 순서로 게시한다.
     *
     * <p>남는 스킬이 없으면 새 버전을 쓰지 않고 빈 {@code external_dirs} 를 게시한 뒤 그 profile 의 버전
     * 디렉터리를 모두 지운다. 게시가 성공한 뒤라 Hermes 가 가리키는 디렉터리가 없다.
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
            publisher.publish(user, agent, List.of());
            store.deleteAll(profile);
        } else {
            publishVersion(user, agent, remaining);
        }
        events.publishEvent(new SkillsChanged(agent.id()));
    }

    /**
     * 대시보드의 전역 켜고 끄기를 쓴다. 편집자만 한다. Hermes 가 가진 스킬도 켜고 끌 수 있어서 이름은 올린
     * 스킬 규칙이 아니라 Hermes 이름 규칙으로 본다.
     */
    public void toggle(CurrentUser user, String code, String name, boolean enabled) {
        Agent agent = requireEditable(user, code);
        if (name == null || !HERMES_SKILL_NAME.matcher(name).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a skill name must follow the Hermes skill name rule");
        }
        publisher.toggle(agent.hermesProfile(), name, enabled);
        events.publishEvent(new SkillsChanged(agent.id()));
    }

    /** 지금 버전의 그 스킬, 없으면 표식 없이 남은 더 새 버전의 그 스킬이다. 둘 다 없으면 {@code null} 이다. */
    private SkillBundle uploadedBundle(String profile, Map<String, SkillBundle> current, String name) {
        SkillBundle bundle = current.get(name);
        return bundle != null ? bundle : store.readPending(profile).get(name);
    }

    private static Set<String> uploadedNames(Map<String, SkillBundle> current, Map<String, SkillBundle> pending) {
        Set<String> names = new HashSet<>(current.keySet());
        names.addAll(pending.keySet());
        return names;
    }

    /** 새 버전을 쓰고 게시하고 표식을 쓰고 옛 버전을 정리한다. 저장과 지우기가 같은 순서를 쓴다. */
    private void publishVersion(CurrentUser user, Agent agent, Map<String, SkillBundle> skills) {
        String profile = agent.hermesProfile();
        String version = store.writeVersion(profile, skills);
        try {
            publisher.publish(user, agent, List.of(store.agentPath(profile, version)));
        } catch (HermesRequestRejected rejected) {
            discardQuietly(profile, version);
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

    private static void requireSkillMd(String name, String skillMd) {
        if (skillMd == null || skillMd.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SKILL.md is required");
        }
        if (skillMd.length() > MAX_CHARS_PER_FILE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "SKILL.md can be at most " + MAX_CHARS_PER_FILE + " characters");
        }
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd);
        if (!name.equals(frontmatter.name())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "SKILL.md frontmatter name must equal the skill name");
        }
    }

    /** 경로 규칙과 수와 중복을 본다. 본문이 온 파일은 글자 수도 본다. */
    private static List<SkillFileInput> requireFiles(List<SkillFileInput> files) {
        List<SkillFileInput> inputs = files == null ? List.of() : files;
        if (inputs.size() > MAX_FILES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a skill can have at most " + MAX_FILES + " files");
        }
        Set<String> paths = new HashSet<>();
        for (SkillFileInput input : inputs) {
            if (input == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a skill file is missing");
            }
            SkillStore.requireFilePath(input.path());
            if (!paths.add(input.path())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "a skill file path was sent more than once: " + input.path());
            }
            if (input.content() != null && input.content().length() > MAX_CHARS_PER_FILE) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "a skill file can be at most " + MAX_CHARS_PER_FILE + " characters: " + input.path());
            }
        }
        return inputs;
    }

    /** 본문이 빠진 파일을 지금 버전에서 채우고, 채운 뒤의 합계 크기를 본다. */
    private static SkillBundle bundleOf(
            String name, String skillMd, List<SkillFileInput> inputs, SkillBundle current) {
        Map<String, String> currentFiles = new HashMap<>();
        if (current != null) {
            current.files().forEach(file -> currentFiles.put(file.path(), file.content()));
        }
        long totalBytes = utf8Bytes(skillMd);
        List<SkillFile> files = new ArrayList<>();
        for (SkillFileInput input : inputs) {
            String content = input.content();
            if (content == null) {
                content = currentFiles.get(input.path());
                if (content == null) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "no current content to keep for the skill file: " + input.path());
                }
            }
            totalBytes += utf8Bytes(content);
            files.add(new SkillFile(input.path(), content));
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a skill can be at most " + MAX_TOTAL_BYTES + " bytes in total");
        }
        return new SkillBundle(name, skillMd, files);
    }

    private static SkillDetail detailOf(SkillBundle bundle) {
        List<SkillFileInfo> files = bundle.files().stream()
                .map(file -> new SkillFileInfo(file.path(), utf8Bytes(file.content())))
                .toList();
        return new SkillDetail(bundle.name(), descriptionOf(bundle.skillMd()), bundle.skillMd(), files);
    }

    /** 저장된 원문의 설명이다. 저장할 때 검사했으므로 읽지 못하면 비워 두고 읽기를 막지 않는다. */
    private static String descriptionOf(String skillMd) {
        try {
            return SkillFrontmatter.parse(skillMd).description();
        } catch (ApiException ex) {
            return "";
        }
    }

    private static long utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.SKILL_NOT_FOUND, "no such uploaded skill");
    }

    private static ApiException forbidden() {
        return new ApiException(
                ErrorCode.FORBIDDEN, "only the owner of this agent or the group admin can manage its skills");
    }
}
