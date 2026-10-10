package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 수락한 모든 요청을 관찰과 같은 수명으로 보관한다. */
@Entity
@Table(
        name = "media_observation_request",
        uniqueConstraints = @UniqueConstraint(columnNames = {"attachment_id", "request_id"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaObservationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "request_id", nullable = false, columnDefinition = "CHAR(36)")
    private String requestId;

    @Column(name = "request_hash", nullable = false, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "observation_id", nullable = false)
    private Long observationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(
                name = "attachment_id",
                referencedColumnName = "attachment_id",
                insertable = false,
                updatable = false),
        @JoinColumn(name = "observation_id", referencedColumnName = "id", insertable = false, updatable = false)
    })
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MediaObservation observation;

    public MediaObservationRequest(Long attachmentId, UUID requestId, String requestHash, Long observationId) {
        this.attachmentId = attachmentId;
        this.requestId = requestId.toString();
        this.requestHash = requestHash;
        this.observationId = observationId;
    }
}
