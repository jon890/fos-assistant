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
 * 에이전트 하나가 쓸 모델 목록의 한 줄이다.
 *
 * <p>{@code rank} 는 1부터 세고 1이 1순위다. 1순위가 막히면 다음 순위로 넘어간다.
 *
 * <p>칸 이름을 {@code option_rank} 로 둔 것은 MySQL 8 이 {@code RANK} 를 예약어로 갖기 때문이다.
 * 코드에서 부르는 이름은 {@code rank} 하나로 맞춘다.
 */
@Entity
@Table(
        name = "agent_model_option",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_agent_model_option",
                        columnNames = {"agent_id", "option_rank"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentModelOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "option_rank", nullable = false)
    private int rank;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private AgentModelOption(Long agentId, int rank, String provider, String model) {
        this.agentId = agentId;
        this.rank = rank;
        this.provider = provider;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public static AgentModelOption of(Long agentId, int rank, ModelOption option) {
        return new AgentModelOption(agentId, rank, option.provider(), option.model());
    }

    public Long id() {
        return id;
    }

    public Long agentId() {
        return agentId;
    }

    public int rank() {
        return rank;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ModelOption toOption() {
        return new ModelOption(provider, model);
    }

    /** 이 줄의 provider 와 모델을 갈아 끼운다. 순위는 그대로 둔다. */
    public void change(ModelOption option) {
        this.provider = option.provider();
        this.model = option.model();
    }
}
