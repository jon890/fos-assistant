package com.bifos.assistant.workspace.application;

import com.bifos.assistant.mcp.application.port.SandboxFiles;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.workspace.config.WorkspaceProperties;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceEntryKind;
import com.bifos.assistant.workspace.domain.WorkspaceOpenedFile;
import com.bifos.assistant.workspace.domain.WorkspacePath;
import com.bifos.assistant.workspace.infra.WorkspaceTree;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 에이전트의 넣기 도구가 실행 공간의 파일 하나를 읽는다(ADR-20261119 / file-space-put). 「실행 공간」 화면과 같은 읽기 전용 루트와
 * {@link WorkspaceTree} 의 링크 규칙을 쓴다.
 *
 * <p>주인 디렉터리는 부르는 쪽이 에이전트에서 정한 주인 키로만 고른다. 로그에 경로를 남기지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
class WorkspaceSandboxFiles implements SandboxFiles {

    /** {@code Agent.sandboxOwner()} 의 꼴이다. 다른 값으로 루트 밖이나 다른 디렉터리를 고르지 못하게 한다. */
    private static final Pattern OWNER_KEY = Pattern.compile("^[ua][0-9]+$");

    private final LiveProperties<WorkspaceProperties> properties;


    @Override
    public SandboxFile read(String sandboxOwner, String relativePath, long maxBytes) {
        Optional<Path> ownerDir = ownerDir(sandboxOwner);
        if (ownerDir.isEmpty()) {
            return SandboxFile.of(Status.MISSING);
        }
        WorkspacePath path;
        try {
            path = WorkspacePath.parse(relativePath);
        } catch (ApiException ex) {
            return SandboxFile.of(Status.MISSING);
        }
        if (path.isRoot()) {
            return SandboxFile.of(Status.MISSING);
        }
        try {
            Optional<WorkspaceEntry> entry = WorkspaceTree.stat(ownerDir.get(), path);
            if (entry.isEmpty() || entry.get().kind() != WorkspaceEntryKind.FILE || !entry.get().readable()) {
                return SandboxFile.of(Status.MISSING);
            }
            if (entry.get().size() != null && entry.get().size() > maxBytes) {
                return SandboxFile.of(Status.TOO_LARGE);
            }
            WorkspaceOpenedFile opened = WorkspaceTree.open(ownerDir.get(), path);
            try (InputStream body = opened.body()) {
                if (opened.size() > maxBytes) {
                    return SandboxFile.of(Status.TOO_LARGE);
                }
                byte[] bytes = body.readNBytes(Math.toIntExact(maxBytes + 1));
                return bytes.length > maxBytes ? SandboxFile.of(Status.TOO_LARGE) : SandboxFile.found(bytes);
            }
        } catch (ApiException | IOException ex) {
            // 없음, 링크, 읽을 수 없음이 모두 여기로 온다. 사용자에게는 넣지 못한 까닭으로 「없음」 만 알린다.
            log.debug("could not read a sandbox file owner={} error={}", sandboxOwner, ex.getClass().getSimpleName());
            return SandboxFile.of(Status.MISSING);
        }
    }


    /** 루트가 설정됐고 주인 디렉터리가 링크가 아닌 디렉터리일 때만 준다. */
    private Optional<Path> ownerDir(String sandboxOwner) {
        if (sandboxOwner == null || !OWNER_KEY.matcher(sandboxOwner).matches()) {
            return Optional.empty();
        }
        WorkspaceProperties current = properties.current();
        if (!current.available() || !WorkspaceTree.isDirectoryNoFollow(current.rootPath())) {
            return Optional.empty();
        }
        Path ownerDir = current.rootPath().resolve(sandboxOwner);
        return WorkspaceTree.isDirectoryNoFollow(ownerDir) ? Optional.of(ownerDir) : Optional.empty();
    }
}
