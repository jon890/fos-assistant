package com.bifos.assistant.workspace.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.workspace.application.model.WorkspaceAgent;
import com.bifos.assistant.workspace.application.model.WorkspaceFile;
import com.bifos.assistant.workspace.application.model.WorkspacePreview;
import com.bifos.assistant.workspace.application.model.WorkspaceStatus;
import com.bifos.assistant.workspace.domain.WorkspaceDeleter;
import com.bifos.assistant.workspace.domain.WorkspaceDeletion;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceListing;
import com.bifos.assistant.workspace.domain.WorkspaceOpenedFile;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import com.bifos.assistant.workspace.infra.WorkspaceTree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 요청자의 실행 공간 디렉터리 {@code u<사용자 번호>} 를 읽는다. 계약은 {@code backend/docs/code-architecture.md} 의 「실행 공간 파일」 이 갖는다.
 *
 * <p>주인 디렉터리는 요청자로만 정한다. 요청 값은 그 디렉터리 안의 상대 경로뿐이다. 읽기의 오류 로그에는 사용자 번호와 예외 종류만
 * 남기고 경로를 남기지 않는다. 지우기는 도우미의 결과마다 경로를 포함한 {@code INFO} 로그 한 줄을 남긴다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkspaceService {

    private static final int LIST_LIMIT = 1_000;
    private static final String DOWNLOAD_TYPE = "application/octet-stream";
    private static final int DELETE_LIMIT = 10_000;

    private final LiveProperties<WorkspaceProperties> properties;
    private final AgentService agents;
    private final UserExecutionLimiter limiter;
    private final WorkspaceDeleter deleter;

    public WorkspaceStatus status(CurrentUser user) {
        List<WorkspaceAgent> shared = agents.readableBy(user).stream()
                .filter(agent -> user.id().equals(agent.ownerUserId()))
                .map(agent ->
                        new WorkspaceAgent(agent.code(), agent.name(), agent.visibility() == AgentVisibility.GROUP))
                .toList();
        int running = limiter.used(user.id());
        WorkspaceProperties current = properties.current();
        Optional<Path> ownerDir = ownerDir(current, user);
        if (ownerDir.isEmpty()) {
            return new WorkspaceStatus(false, false, false, running, shared);
        }
        return new WorkspaceStatus(
                true, current.deletable(), WorkspaceTree.isDirectoryNoFollow(ownerDir.get()), running, shared);
    }

    public WorkspaceListing list(CurrentUser user, String rawPath) {
        return list(user, rawPath, null);
    }

    public WorkspaceListing list(CurrentUser user, String rawPath, String cursor) {
        Path ownerDir = requireOwnerDir(user);
        WorkspacePath path = WorkspacePath.parse(rawPath);
        try {
            return WorkspaceTree.list(ownerDir, path, LIST_LIMIT, cursor).orElseThrow(WorkspaceService::notFound);
        } catch (IOException ex) {
            throw readFailed(user, ex);
        }
    }

    /**
     * 본문을 연다. 미리보기는 확장자로 형식을 정하고 크기 상한을 열기 전에 본다. 내려받기는 형식과 크기를 보지 않는다.
     *
     * @param rawPathFromPathVariable {@code {*path}} 가 준 디코딩한 경로. 앞의 {@code /} 를 뗀다
     */
    public WorkspaceFile open(CurrentUser user, String rawPathFromPathVariable, boolean download) {
        Path ownerDir = requireOwnerDir(user);
        String relative = rawPathFromPathVariable.startsWith("/")
                ? rawPathFromPathVariable.substring(1)
                : rawPathFromPathVariable;
        WorkspacePath path = WorkspacePath.ofSegments(List.of(relative.split("/", -1)));
        try {
            if (download) {
                WorkspaceOpenedFile opened = WorkspaceTree.open(ownerDir, path);
                return new WorkspaceFile(path.name(), DOWNLOAD_TYPE, opened.size(), false, true, opened.body());
            }
            WorkspacePreview preview = WorkspacePreviewPolicy.of(path.name())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.WORKSPACE_PREVIEW_UNSUPPORTED, "workspace preview is not supported"));
            Optional<Long> size = WorkspaceTree.stat(ownerDir, path).map(WorkspaceEntry::size);
            if (size.isPresent() && size.get() > preview.maxBytes()) {
                throw tooLarge();
            }
            WorkspaceOpenedFile opened = WorkspaceTree.open(ownerDir, path);
            if (opened.size() > preview.maxBytes()) {
                // 확인한 뒤 여는 사이에 커졌다. 연 스트림을 닫고 같은 응답을 준다.
                opened.body().close();
                throw tooLarge();
            }
            return new WorkspaceFile(
                    path.name(), preview.contentType(), opened.size(), preview.html(), false, opened.body());
        } catch (IOException ex) {
            throw readFailed(user, ex);
        }
    }

    /**
     * 경로 하나를 운영의 권한 도우미로 지운다. 읽기 마운트에서 없는 경로는 도우미를 부르지 않는다. 도우미도 같은 검사를 다시 한다.
     */
    public WorkspaceDeletion delete(CurrentUser user, String rawPath) {
        if (!properties.current().deletable()) {
            throw new ApiException(ErrorCode.WORKSPACE_DELETE_UNAVAILABLE, "workspace delete is not configured");
        }
        Path ownerDir = requireOwnerDir(user);
        WorkspacePath path = WorkspacePath.parse(rawPath);
        if (path.isRoot()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "workspace delete needs a path");
        }
        WorkspaceEntry entry;
        try {
            entry = WorkspaceTree.stat(ownerDir, path).orElseThrow(WorkspaceService::notFound);
        } catch (IOException ex) {
            throw readFailed(user, ex);
        }
        try {
            WorkspaceDeletion deleted = deleter.delete(ownerKey(user), path, DELETE_LIMIT);
            logDelete(user, path, deleted.kind().name(), String.valueOf(deleted.entries()), "OK");
            return deleted;
        } catch (ApiException ex) {
            // 도우미가 실패하면 일부가 지워졌을 수 있어 지운 항목 수를 모른다.
            logDelete(user, path, entry.kind().name(), "-", ex.code().name());
            throw ex;
        }
    }

    private static void logDelete(CurrentUser user, WorkspacePath path, String kind, String entries, String result) {
        log.info(
                "workspace delete user={} path={} kind={} entries={} result={}",
                user.id(),
                escapeForLog(path.value()),
                kind,
                entries,
                result);
    }

    /** 루트가 설정되었고 링크가 아닌 디렉터리일 때만 요청자의 디렉터리를 준다. */
    private static Optional<Path> ownerDir(WorkspaceProperties current, CurrentUser user) {
        if (!current.available() || !WorkspaceTree.isDirectoryNoFollow(current.rootPath())) {
            return Optional.empty();
        }
        return Optional.of(current.rootPath().resolve(ownerKey(user)));
    }

    /** 실행 공간의 주인 키다. 요청자로만 만든다. */
    private static String ownerKey(CurrentUser user) {
        return "u" + user.id();
    }

    /**
     * 로그 줄을 끊거나 꾸밀 수 있는 문자를 역슬래시, {@code u}, 대문자 16진수 네 자리로 바꾼다. C0 제어 문자와 DEL, C1 제어 문자, 줄 구분자
     * U+2028 과 문단 구분자 U+2029 가 대상이다.
     */
    static String escapeForLog(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029) {
                escaped.append(String.format("\\u%04X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private Path requireOwnerDir(CurrentUser user) {
        return ownerDir(properties.current(), user)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKSPACE_UNAVAILABLE, "workspace is not configured"));
    }

    private static ApiException readFailed(CurrentUser user, IOException ex) {
        log.warn(
                "workspace read failed user={} error={}",
                user.id(),
                ex.getClass().getSimpleName());
        return new ApiException(ErrorCode.INTERNAL_ERROR, "workspace read failed");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.WORKSPACE_ENTRY_NOT_FOUND, "workspace entry not found");
    }

    private static ApiException tooLarge() {
        return new ApiException(ErrorCode.WORKSPACE_PREVIEW_TOO_LARGE, "workspace preview is too large");
    }
}
