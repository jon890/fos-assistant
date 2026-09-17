package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Workspace this conversation runs in. Null means no workspace, and stays null for its life. */
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "agent_id")
    private Long agentId;

    /** Hermes session this conversation continues. Null until the first run reports one. */
    @Column(name = "hermes_session_id", length = 128)
    private String hermesSessionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    private Conversation(Long userId, String title, Long workspaceId, Long agentId) {
        this.userId = userId;
        this.title = title;
        this.workspaceId = workspaceId;
        this.agentId = agentId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Conversation startedBy(Long userId, String title, Long workspaceId, Long agentId) {
        return new Conversation(userId, title, workspaceId, agentId);
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    /** Fixed at the first turn. A later message cannot move a conversation into another workspace. */
    public Long workspaceId() {
        return workspaceId;
    }

    public Long agentId() { return agentId; }

    public String hermesSessionId() {
        return hermesSessionId;
    }

    public String title() {
        return title;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void rememberSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.hermesSessionId = sessionId;
        }
        this.updatedAt = Instant.now();
    }
}
