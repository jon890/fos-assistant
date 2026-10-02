package com.bifos.assistant.chat.domain;

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

/**
 * 그룹이 숨긴 provider 또는 모델 하나다.
 *
 * <p>숨긴 것만 적는다. 적히지 않은 provider 와 모델은 새로 생긴 것까지 모두 보인다.
 */
@Entity
@Table(name = "model_hidden", uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "provider", "model"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelHidden {

    /** {@code model} 칸에서 그 provider 전체를 뜻하는 값이다. */
    public static final String WHOLE_PROVIDER = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "provider", nullable = false, length = ModelChoice.PROVIDER_MAX_LENGTH)
    private String provider;

    /** 숨긴 모델 이름이다. {@link #WHOLE_PROVIDER} 면 그 provider 전체다. */
    @Column(name = "model", nullable = false, length = ModelChoice.MODEL_MAX_LENGTH)
    private String model;

    private ModelHidden(Long groupId, String provider, String model) {
        this.groupId = groupId;
        this.provider = provider;
        this.model = model;
    }

    /** @param model null 이면 그 provider 전체를 숨긴다 */
    public static ModelHidden of(Long groupId, String provider, String model) {
        return new ModelHidden(groupId, provider, model == null ? WHOLE_PROVIDER : model);
    }

    public boolean wholeProvider() {
        return WHOLE_PROVIDER.equals(model);
    }
}
