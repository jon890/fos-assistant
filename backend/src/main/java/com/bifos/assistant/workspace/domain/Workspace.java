package com.bifos.assistant.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One area of work, backed by a directory of agent definitions outside this service.
 *
 * <p>The definitions stay in their own repository and are read, never written. That repository
 * decided not to depend on any agent runtime, so the dependency may only point this way.
 *
 * <p>{@link WorkspaceVisibility} is required rather than defaulted. Some areas hold health records
 * or job applications, and a workspace that becomes family-visible by omission has already leaked
 * before anyone notices.
 */
@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Directory name under the configured workspace root. Never an absolute path. */
    @Column(name = "source_path", nullable = false, length = 255)
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private WorkspaceVisibility visibility;

    /** Required when the workspace is private. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Workspace() {
    }

    private Workspace(
            String code,
            String name,
            String sourcePath,
            WorkspaceVisibility visibility,
            Long ownerUserId) {
        this.code = code;
        this.name = name;
        this.sourcePath = sourcePath;
        this.visibility = visibility;
        this.ownerUserId = ownerUserId;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public static Workspace of(
            String code,
            String name,
            String sourcePath,
            WorkspaceVisibility visibility,
            Long ownerUserId) {
        return new Workspace(code, name, sourcePath, visibility, ownerUserId);
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String sourcePath() {
        return sourcePath;
    }

    public WorkspaceVisibility visibility() {
        return visibility;
    }

    public Long ownerUserId() {
        return ownerUserId;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isReadableBy(Long userId) {
        if (!enabled) {
            return false;
        }
        return visibility == WorkspaceVisibility.FAMILY || java.util.Objects.equals(ownerUserId, userId);
    }
}
