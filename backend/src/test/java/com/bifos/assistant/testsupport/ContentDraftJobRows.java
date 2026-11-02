package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftSnapshotRepository;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 원고 저장 job 은 snapshot 줄의 칸이다. 검사가 job 만 세고 읽고 고쳐 쓰도록 snapshot 저장소를 감싼다. 읽은 job 을 고쳐
 * {@link #saveAndFlush} 로 넘기면 그 job 을 가진 snapshot 을 저장한다.
 */
public final class ContentDraftJobRows {
    private final Supplier<ContentDraftSnapshotRepository> snapshots;
    private final Map<ContentDraftJob, ContentDraftSnapshot> owners = new IdentityHashMap<>();


    public ContentDraftJobRows(Supplier<ContentDraftSnapshotRepository> snapshots) {
        this.snapshots = snapshots;
    }


    /** job 이 있는 snapshot 의 job 이다. snapshot 번호 순서다. */
    public List<ContentDraftJob> findAll() {
        return snapshots.get().findAll().stream().filter(row -> row.job() != null)
            .sorted(Comparator.comparing(ContentDraftSnapshot::id)).map(this::remember).toList();
    }


    public long count() {
        return findAll().size();
    }


    public Optional<ContentDraftJob> findByJobId(String jobId) {
        return snapshots.get().findByJobJobId(jobId).map(this::remember);
    }


    public ContentDraftJob saveAndFlush(ContentDraftJob job) {
        var owner = owners.get(job);
        if (owner == null) {
            throw new IllegalArgumentException("job was not read through this helper");
        }
        return snapshots.get().saveAndFlush(owner).job();
    }


    private ContentDraftJob remember(ContentDraftSnapshot row) {
        owners.put(row.job(), row);
        return row.job();
    }
}
