package com.bifos.assistant.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 새 대화 화면에서 에이전트를 골랐을 때 보이는 추천 질문 한 줄이다.
 *
 * <p>{@code position} 은 0부터 세는 보이는 차례다. 고칠 때는 그 에이전트의 줄을 모두 지우고 새로 넣는다.
 */
@Entity
@Table(
        name = "agent_starter_prompt",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_agent_starter_prompt",
                        columnNames = {"agent_id", "position"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentStarterPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "text", nullable = false, length = 300)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private AgentStarterPrompt(Long agentId, int position, String text) {
        this.agentId = agentId;
        this.position = position;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public static AgentStarterPrompt of(Long agentId, int position, String text) {
        return new AgentStarterPrompt(agentId, position, text);
    }

    public Long id() {
        return id;
    }

    public Long agentId() {
        return agentId;
    }

    public int position() {
        return position;
    }

    public String text() {
        return text;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
