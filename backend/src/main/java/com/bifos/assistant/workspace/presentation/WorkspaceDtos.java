package com.bifos.assistant.workspace.presentation;

import com.bifos.assistant.workspace.application.model.WorkspaceAgent;
import com.bifos.assistant.workspace.application.model.WorkspaceStatus;
import com.bifos.assistant.workspace.domain.WorkspaceDeletion;
import com.bifos.assistant.workspace.domain.WorkspaceEntry;
import com.bifos.assistant.workspace.domain.WorkspaceListing;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 파일 공간 경로의 응답 모양이다. 계약은 {@code docs/code-architecture.md} 의 「실행 공간 파일」 의 「API」 가 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WorkspaceDtos {

    public record StatusView(
            boolean available, boolean deletable, boolean exists, int runningExecutions, List<AgentView> agents) {

        static StatusView of(WorkspaceStatus status) {
            return new StatusView(
                    status.available(),
                    status.deletable(),
                    status.exists(),
                    status.runningExecutions(),
                    status.agents().stream().map(AgentView::of).toList());
        }
    }

    public record AgentView(String code, String name, boolean shared) {

        static AgentView of(WorkspaceAgent agent) {
            return new AgentView(agent.code(), agent.name(), agent.shared());
        }
    }

    public record ListingView(String path, List<EntryView> entries, boolean truncated) {

        static ListingView of(WorkspaceListing listing) {
            return new ListingView(
                    listing.path(),
                    listing.entries().stream().map(EntryView::of).toList(),
                    listing.truncated());
        }
    }

    /**
     * 목록의 한 줄이다.
     *
     * @param size {@code FILE} 만 채운다
     * @param modifiedAt ISO-8601 문자열
     */
    public record EntryView(
            String name, String kind, Long size, String modifiedAt, boolean readable, boolean openable) {

        static EntryView of(WorkspaceEntry entry) {
            return new EntryView(
                    entry.name(),
                    entry.kind().name(),
                    entry.size(),
                    entry.modifiedAt().toString(),
                    entry.readable(),
                    entry.openable());
        }
    }

    /**
     * 지우기의 결과다.
     *
     * @param entries 지운 항목 수
     * @param bytes 지운 일반 파일의 크기 합
     */
    public record DeletionView(String kind, long entries, long bytes) {

        static DeletionView of(WorkspaceDeletion deletion) {
            return new DeletionView(deletion.kind().name(), deletion.entries(), deletion.bytes());
        }
    }
}
