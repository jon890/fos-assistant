package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 사용자 한 사람과 커넥터 하나의 연결이다(ADR-043).
 *
 * <p>상태는 「값이 확인돼 쓸 수 있는가」 다. 에이전트에 설치되고 반영됐는지는 바인딩({@link ConnectorBinding})이 갖는다.
 * 연결의 메서드는 에이전트를 켜거나 끄지 않는다(ADR-083).
 *
 * <p>표의 {@code restart_required}, {@code desired_enabled} 칸은 쓰지 않는 칸으로 남아 매핑하지 않는다. {@code agent_id} 는 지운
 * 에이전트 정리의 벌크 갱신만 쓰는 읽기 전용 칸으로 매핑한다. 새 행에서 {@code agent_id} 는 비고 두 boolean 칸은 기본값으로 저장된다.
 *
 * <p>상태를 바꾸는 메서드는 시각을 인자로 받는다. 비밀 칸의 원문을 받는 메서드는 두지 않는다.
 */
@Entity
@Table(
        name = "connector_connection",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_connector_connection_user_connector",
                        columnNames = {"user_id", "connector_id"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectorConnection {
    /** {@code fields} 열의 크기다. 저장할 JSON 텍스트가 이보다 길면 등록을 받지 않는다. */
    public static final int FIELDS_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "connector_id", nullable = false, length = 64)
    private String connectorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    /** JSON 텍스트 열이다. {@code ConnectionFieldsConverter} 가 자동으로 읽고 쓴다. */
    @Column(nullable = false, length = FIELDS_LENGTH)
    private ConnectionFields fields;

    @Column(name = "checked_at")
    private Instant checkedAt;

    /** 연결의 칸 값을 대시보드 plugin 의 보관 파일에 둔 적이 있다. 옛 연결은 연결 확인이 값을 옮길 때 참이 된다(ADR-083). */
    @Column(name = "vault_stored", nullable = false)
    private boolean vaultStored;

    /** 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 가 선언하지 않은 수다. 그 도구의 호출은 거절된다. */
    @Column(name = "undeclared_tools", nullable = false)
    private int undeclaredTools;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 바인딩 앞의 옛 커넥터 에이전트다. 엔티티는 쓰지 않고, 지운 에이전트를 정리할 때 벌크 갱신만 비운다(ADR-20261009 / agent-purge). */
    @Getter(AccessLevel.NONE)
    @Column(name = "agent_id", insertable = false, updatable = false)
    private Long legacyAgentId;

    private ConnectorConnection(Long userId, String connectorId, Instant now) {
        this.userId = userId;
        this.connectorId = connectorId;
        this.status = ConnectionStatus.PENDING;
        this.fields = ConnectionFields.empty();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 에이전트 없는 연결이다. 값은 보관 파일에 두고 에이전트에는 바인딩으로 붙인다(ADR-083). */
    public static ConnectorConnection pending(Long userId, String connectorId, Instant now) {
        return new ConnectorConnection(userId, connectorId, now);
    }

    /**
     * 보관 파일의 이름이다. 연결 번호 앞에 {@code c} 를 붙인다. 저장한 뒤에만 부른다.
     *
     * <p>형식은 {@code backend/docs/flow.md} 의 「보관 파일」 이 갖는다.
     */
    public String vault() {
        return "c" + id;
    }

    /**
     * 확인 도구가 통과한 값을 보관 파일에 썼다. 비밀이 아닌 칸 값과 비밀 앞부분을 적고 쓸 수 있는 연결로 둔다.
     *
     * <p>세어 둔 선언 밖 도구 수는 값이 바뀌었으므로 비운다. 다음 연결 확인이 다시 센다.
     */
    public void connected(ConnectionFields fields, Instant now) {
        this.fields = fields;
        this.vaultStored = true;
        this.undeclaredTools = 0;
        this.status = ConnectionStatus.READY;
        this.checkedAt = now;
        this.updatedAt = now;
    }

    /** 확인 도구가 통과했다. */
    public void ready(Instant now) {
        this.status = ConnectionStatus.READY;
        this.checkedAt = now;
        this.updatedAt = now;
    }

    public void pending(Instant now) {
        this.status = ConnectionStatus.PENDING;
        this.updatedAt = now;
    }

    public void markVaultStored() {
        this.vaultStored = true;
    }

    public void recordUndeclaredTools(int count) {
        this.undeclaredTools = count;
    }

    /**
     * 바인딩을 모두 떼고 보관 파일을 지웠다. 행은 이력을 위해 남기고 칸 값과 비밀 앞부분을 비운다.
     *
     * <p>재시작 대기는 바인딩이 갖고, 뗀 바인딩은 행이 지워지므로 여기 남길 대기가 없다.
     */
    public void disconnected(Instant now) {
        this.status = ConnectionStatus.DISCONNECTED;
        this.fields = ConnectionFields.empty();
        this.vaultStored = false;
        this.undeclaredTools = 0;
        this.checkedAt = now;
        this.updatedAt = now;
    }
}
