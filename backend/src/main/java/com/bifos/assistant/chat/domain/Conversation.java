package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "agent_id")
    private Long agentId;

    /** 첫 실행이 session을 보고할 때까지 비어 있는 Hermes session이다. */
    @Column(name = "hermes_session_id", length = 128)
    private String hermesSessionId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Conversation(Long userId, String title, Long agentId) {
        this.userId = userId;
        this.title = title;
        this.agentId = agentId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static Conversation startedBy(Long userId, String title, Long agentId) {
        return new Conversation(userId, title, agentId);
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
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

    /** 제목이 비어 있을 때만 채운다. 사진을 먼저 올리려고 만든 대화는 첫 메시지가 제목을 정한다. */
    public void titleIfBlank(String title) {
        if (this.title == null || this.title.isBlank()) {
            this.title = title;
        }
    }

    public void rememberSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.hermesSessionId = sessionId;
        }
        this.updatedAt = Instant.now();
    }
}
