package com.bifos.assistant.agent.domain;

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

/** 그룹의 도구 선택 화면에서 숨긴 toolset이다. 활성 상태는 Hermes profile에 남는다. */
@Entity
@Table(name = "toolset_hidden", uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "name"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToolsetHidden {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 64)
    private String name;

    public static ToolsetHidden of(Long groupId, String name) {
        ToolsetHidden row = new ToolsetHidden();
        row.groupId = groupId;
        row.name = name;
        return row;
    }
}
