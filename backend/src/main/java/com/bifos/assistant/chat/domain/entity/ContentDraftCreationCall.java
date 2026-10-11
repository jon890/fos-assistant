package com.bifos.assistant.chat.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 원고 생성의 재시도 식별만 보존하며 본문과 권한 증명은 저장하지 않는다. */
@Entity
@Table(name = "content_draft_creation_call", uniqueConstraints = {
    @UniqueConstraint(name = "uq_content_creation_call_key", columnNames = "call_key"),
    @UniqueConstraint(name = "uq_content_creation_call_identity", columnNames = "identity_key")
})
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftCreationCall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_key", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] callKey;

    @Column(name = "identity_key", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] identityKey;

    @Column(name = "creation_request_id", nullable = false)
    private Long creationRequestId;

    @Column(name = "session_id", nullable = false, columnDefinition = "LONGTEXT")
    private String sessionId;

    @Column(name = "tool_call_id", nullable = false, columnDefinition = "LONGTEXT")
    private String toolCallId;

    public ContentDraftCreationCall(byte[] callKey, byte[] identityKey, Long creationRequestId, String sessionId, String toolCallId) {
        this.callKey = callKey;
        this.identityKey = identityKey;
        this.creationRequestId = creationRequestId;
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
    }
}
