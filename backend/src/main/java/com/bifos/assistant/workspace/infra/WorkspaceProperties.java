package com.bifos.assistant.workspace.infra;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실행 공간 파일의 설정이다({@code docs/code-architecture.md} 의 「실행 공간 파일」).
 *
 * <p>두 값 모두 비어 있어도 기동한다.
 *
 * @param root 실행 공간 루트를 Control Plane 에서 본 경로. 비어 있으면 모든 경로가 {@code WORKSPACE_UNAVAILABLE} 이다
 * @param deleteSocket 지우기를 맡는 운영 도우미의 unix socket. 비어 있으면 지우기를 열지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.sandbox-workspace")
public record WorkspaceProperties(String root, String deleteSocket) {

    /** 루트가 설정되었다. 디렉터리인지는 부르는 쪽이 본다. */
    public boolean available() {
        return root != null && !root.isBlank();
    }

    /** 지우기 도우미의 socket 이 설정되었다. */
    public boolean deletable() {
        return deleteSocket != null && !deleteSocket.isBlank();
    }

    /** {@link #available()} 일 때만 부른다. */
    public Path rootPath() {
        return Path.of(root);
    }
}
