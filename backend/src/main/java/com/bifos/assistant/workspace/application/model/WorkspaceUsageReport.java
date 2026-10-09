package com.bifos.assistant.workspace.application.model;

import java.util.List;

/**
 * 관리자가 보는 실행 공간별 용량이다.
 *
 * @param available 루트가 설정되었고 링크가 아닌 디렉터리다. 거짓이면 {@code spaces} 가 비어 있다
 * @param spaces {@code bytes} 가 큰 순서다
 */
public record WorkspaceUsageReport(boolean available, List<WorkspaceSpaceUsage> spaces) {}
