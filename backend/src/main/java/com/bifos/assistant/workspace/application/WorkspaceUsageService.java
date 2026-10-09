package com.bifos.assistant.workspace.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.workspace.application.model.WorkspaceSpaceKind;
import com.bifos.assistant.workspace.application.model.WorkspaceSpaceUsage;
import com.bifos.assistant.workspace.application.model.WorkspaceUsageReport;
import com.bifos.assistant.workspace.domain.WorkspaceMeasured;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import com.bifos.assistant.workspace.infra.WorkspaceTree;
import com.bifos.assistant.workspace.infra.WorkspaceUsageWalker;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 관리자가 보는 실행 공간별 용량을 요청할 때 센다. 계약은 {@code docs/code-architecture.md} 의 「관리자 용량」 이 갖는다.
 *
 * <p>루트 바로 아래에서 이름이 {@code u<번호>} 나 {@code a<번호>} 이고 링크가 아닌 디렉터리만 센다. 번호가 0 으로 시작하면 세지 않는다.
 * 공간마다 항목 수 상한과 요청 전체의 시간 상한이 있다. 결과와 로그에 파일 이름과 경로를 싣지 않는다.
 */
@Service
@Slf4j
public class WorkspaceUsageService {

    private static final long MAX_ENTRIES_PER_SPACE = 200_000;
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final Pattern USER_DIR = Pattern.compile("^u([1-9]\\d*)$");
    private static final Pattern AGENT_DIR = Pattern.compile("^a([1-9]\\d*)$");
    private static final Comparator<WorkspaceSpaceUsage> LARGEST_FIRST = Comparator.comparingLong(
                    WorkspaceSpaceUsage::bytes)
            .reversed()
            .thenComparing(WorkspaceSpaceUsage::kind)
            .thenComparingLong(WorkspaceSpaceUsage::id);

    private final LiveProperties<WorkspaceProperties> properties;
    private final UserDisplayNameService userNames;
    private final AgentService agents;
    private final Clock clock;
    private final long maxEntriesPerSpace;
    private final Duration budget;

    @Autowired
    public WorkspaceUsageService(
            LiveProperties<WorkspaceProperties> properties,
            UserDisplayNameService userNames,
            AgentService agents,
            Clock clock) {
        this(properties, userNames, agents, clock, MAX_ENTRIES_PER_SPACE, BUDGET);
    }

    /** 상한을 작게 주어 멈추는 경우를 검사할 때 쓴다. */
    WorkspaceUsageService(
            LiveProperties<WorkspaceProperties> properties,
            UserDisplayNameService userNames,
            AgentService agents,
            Clock clock,
            long maxEntriesPerSpace,
            Duration budget) {
        this.properties = properties;
        this.userNames = userNames;
        this.agents = agents;
        this.clock = clock;
        this.maxEntriesPerSpace = maxEntriesPerSpace;
        this.budget = budget;
    }

    /**
     * 공간을 번호 순서로 차례로 센다. 요청 전체의 시간이 지난 뒤의 공간은 세지 않고 {@code bytes} 와 {@code entries} 를 0,
     * {@code partial} 을 참으로 둔다.
     */
    public WorkspaceUsageReport report() {
        WorkspaceProperties current = properties.current();
        if (!current.available() || !WorkspaceTree.isDirectoryNoFollow(current.rootPath())) {
            return new WorkspaceUsageReport(false, List.of());
        }
        Path root = current.rootPath();
        List<Space> spaces = spaces(root);
        Map<Long, Agent> agentsById = agents.byIds(spaces.stream()
                .filter(space -> space.kind() == WorkspaceSpaceKind.AGENT)
                .map(Space::id)
                .toList());
        Instant deadline = clock.instant().plus(budget);
        List<WorkspaceSpaceUsage> usages = new ArrayList<>();
        for (Space space : spaces) {
            WorkspaceMeasured measured = clock.instant().isAfter(deadline)
                    ? new WorkspaceMeasured(0, 0, true)
                    : WorkspaceUsageWalker.measure(root.resolve(space.dirName()), maxEntriesPerSpace, deadline, clock);
            usages.add(new WorkspaceSpaceUsage(
                    space.kind(),
                    space.id(),
                    nameOf(space, agentsById),
                    measured.bytes(),
                    measured.entries(),
                    measured.partial()));
        }
        usages.sort(LARGEST_FIRST);
        return new WorkspaceUsageReport(true, List.copyOf(usages));
    }

    /** 루트 바로 아래의 공간이다. 이름 규칙에 맞고 링크가 아닌 디렉터리만 고르고, 종류와 번호 순서로 둔다. */
    private static List<Space> spaces(Path root) {
        List<Space> spaces = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                Space space = spaceOf(name);
                if (space != null && WorkspaceTree.isDirectoryNoFollow(entry)) {
                    spaces.add(space);
                }
            }
        } catch (IOException | DirectoryIteratorException ex) {
            log.warn("workspace usage read failed error={}", ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "workspace read failed");
        }
        spaces.sort(Comparator.comparing(Space::kind).thenComparingLong(Space::id));
        return spaces;
    }

    /** 이름 규칙에 맞지 않거나 번호가 {@code long} 을 넘으면 {@code null} 이다. */
    private static Space spaceOf(String name) {
        Matcher user = USER_DIR.matcher(name);
        Matcher agent = AGENT_DIR.matcher(name);
        WorkspaceSpaceKind kind;
        String digits;
        if (user.matches()) {
            kind = WorkspaceSpaceKind.USER;
            digits = user.group(1);
        } else if (agent.matches()) {
            kind = WorkspaceSpaceKind.AGENT;
            digits = agent.group(1);
        } else {
            return null;
        }
        try {
            return new Space(kind, Long.parseLong(digits), name);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String nameOf(Space space, Map<Long, Agent> agentsById) {
        if (space.kind() == WorkspaceSpaceKind.USER) {
            return userNames.find(space.id());
        }
        Agent agent = agentsById.get(space.id());
        return agent == null ? null : agent.name();
    }

    /** 루트 바로 아래에서 고른 공간 디렉터리 하나다. */
    private record Space(WorkspaceSpaceKind kind, long id, String dirName) {}
}
