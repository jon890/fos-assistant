package com.bifos.assistant.admin.workspace.presentation;

import com.bifos.assistant.workspace.application.model.WorkspaceSpaceUsage;
import com.bifos.assistant.workspace.application.model.WorkspaceUsageReport;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 작업 공간 사용량 경로가 주고받는 모양이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspaceAdminDtos {

    /** 관리자가 보는 공간별 용량이다. 파일 이름과 경로는 없다. */
    public record AdminUsageView(boolean available, List<AdminSpaceView> spaces) {

        static AdminUsageView of(WorkspaceUsageReport report) {
            return new AdminUsageView(
                report.available(),
                report.spaces().stream().map(AdminSpaceView::of).toList());
        }
    }

    /**
     * 공간 하나의 용량이다.
     *
     * @param kind {@code USER} 나 {@code AGENT}
     * @param name 사용자 이름이나 에이전트 이름. 찾지 못하면 {@code null}
     * @param partial 상한에 닿았거나 읽지 못한 디렉터리가 있어 일부만 셌다
     */
    public record AdminSpaceView(String kind, long id, String name, long bytes, long entries, boolean partial) {

        static AdminSpaceView of(WorkspaceSpaceUsage space) {
            return new AdminSpaceView(
                space.kind().name(), space.id(), space.name(), space.bytes(), space.entries(), space.partial());
        }
    }
}
