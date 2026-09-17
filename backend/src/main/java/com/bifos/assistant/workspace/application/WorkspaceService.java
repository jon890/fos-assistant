package com.bifos.assistant.workspace.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.workspace.domain.Workspace;
import com.bifos.assistant.workspace.infra.WorkspaceRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a workspace and turns its guide into text the agent can be given.
 *
 * <p>The guide is read from a read-only mount of the agent definition repository. That repository
 * is the single source for what each area knows and how it works, so this service copies nothing
 * and stores nothing back.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String GUIDE_FILE = "AGENTS.md";

    private final WorkspaceRepository workspaces;
    private final Path root;
    private final int briefingLimit;

    public WorkspaceService(WorkspaceRepository workspaces, WorkspaceProperties properties) {
        this.workspaces = workspaces;
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.briefingLimit = properties.briefingLimit();
    }

    public List<Workspace> readableBy(CurrentUser user) {
        return workspaces.findByEnabledTrueOrderByCodeAsc().stream()
                .filter(workspace -> workspace.isReadableBy(user.id()))
                .toList();
    }

    public Workspace requireReadable(CurrentUser user, String code) {
        Workspace workspace =
                workspaces
                        .findByCode(code)
                        .orElseThrow(
                                () -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "no such work area"));
        if (!workspace.isReadableBy(user.id())) {
            // Same error as a missing one. Telling a caller that a private area exists is itself a leak.
            throw new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "no such work area");
        }
        return workspace;
    }

    public Workspace requireById(Long id) {
        return workspaces
                .findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, "no such work area"));
    }

    /**
     * Looks up the workspace a conversation already recorded, without a readability check.
     *
     * <p>The check already happened once, when that conversation started. A later member removal or
     * visibility change must not retroactively break a conversation already under way; it only stops
     * new conversations from entering that workspace.
     */
    public Workspace findByIdOrNull(Long id) {
        return id == null ? null : workspaces.findById(id).orElse(null);
    }

    /**
     * Reads the workspace guide as run instructions.
     *
     * <p>Returns null when the guide cannot be read. A missing file must not stop the turn: the
     * agent then answers without the area's rules, which is worse than useless but not a failure the
     * caller can act on.
     */
    public String briefing(Workspace workspace) {
        Path guide = resolveGuide(workspace);
        if (guide == null || !Files.isReadable(guide)) {
            log.warn("workspace {} has no readable guide", workspace.code());
            return null;
        }
        String body;
        try {
            body = Files.readString(guide);
        } catch (IOException ex) {
            log.warn("could not read the guide for workspace {}", workspace.code(), ex);
            return null;
        }
        if (body.length() > briefingLimit) {
            body = body.substring(0, briefingLimit);
        }
        return """
                # 작업 영역: %s

                아래는 이 영역의 운영 규칙이다. 이 대화는 이 영역 안에서 답한다.

                %s"""
                .formatted(workspace.name(), body);
    }

    /** Keeps a stored path from reaching outside the mount, whatever an admin typed. */
    private Path resolveGuide(Workspace workspace) {
        Path candidate = root.resolve(workspace.sourcePath()).normalize();
        if (!candidate.startsWith(root)) {
            log.warn("workspace {} points outside the workspace root", workspace.code());
            return null;
        }
        return candidate.resolve(GUIDE_FILE);
    }
}
