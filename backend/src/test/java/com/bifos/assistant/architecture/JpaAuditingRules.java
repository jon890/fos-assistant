package com.bifos.assistant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.Auditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** 순수 저장 시각만 base로 모으고 업무 시각과 누락은 기준 파일 없이 검사한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class JpaAuditingRules {
    static final String CREATED = "com.bifos.assistant.shared.domain.CreatedAtEntity";
    static final String AUDITED = "com.bifos.assistant.shared.domain.AuditedEntity";
    static final String CONFIG = "com.bifos.assistant.shared.config.JpaAuditingConfig";


    record Contract(String table, String policy) {
    }


    static final Map<String, Contract> ENTITIES = Map.ofEntries(
        Map.entry("com.bifos.assistant.agent.domain.Agent", new Contract("agent", "C+U")),
        Map.entry("com.bifos.assistant.agent.domain.AgentMemoryCollection", new Contract("agent_memory_collection", "C")),
        Map.entry("com.bifos.assistant.attention.domain.AttentionControlEntry", new Contract("attention_control", "C+U")),
        Map.entry("com.bifos.assistant.attention.domain.AttentionEvent", new Contract("attention_event", "제외")),
        Map.entry("com.bifos.assistant.browser.domain.UserBrowser", new Contract("user_browser", "C")),
        Map.entry("com.bifos.assistant.chat.artifact.domain.ChatArtifact", new Contract("chat_artifact", "C")),
        Map.entry("com.bifos.assistant.chat.attachment.domain.ChatAttachment", new Contract("chat_attachment", "C")),
        Map.entry("com.bifos.assistant.chat.conversation.domain.ChatMessage", new Contract("chat_message", "제외")),
        Map.entry("com.bifos.assistant.chat.conversation.domain.ChatPendingMessage", new Contract("chat_pending_message", "제외")),
        Map.entry("com.bifos.assistant.chat.conversation.domain.Conversation", new Contract("conversation", "C")),
        Map.entry("com.bifos.assistant.chat.conversation.domain.ExecutionQuestion", new Contract("execution_question", "C")),
        Map.entry("com.bifos.assistant.chat.attachment.domain.MediaObservation", new Contract("media_observation", "제외")),
        Map.entry("com.bifos.assistant.chat.delivery.domain.ResultDelivery", new Contract("result_delivery", "C")),
        Map.entry("com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraft", new Contract("content_draft", "C")),
        Map.entry("com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationRequest", new Contract("content_draft_creation_request", "C")),
        Map.entry("com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftJob", new Contract("content_draft_job", "제외")),
        Map.entry("com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftRevision", new Contract("content_draft_revision", "제외")),
        Map.entry("com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftSnapshot", new Contract("content_draft_snapshot", "C")),
        Map.entry("com.bifos.assistant.connector.action.domain.ConnectorAction", new Contract("connector_action", "제외")),
        Map.entry("com.bifos.assistant.connector.action.domain.ConnectorActionExecution", new Contract("connector_action_execution", "C")),
        Map.entry("com.bifos.assistant.connector.connection.domain.ConnectorBinding", new Contract("agent_connector_binding", "C")),
        Map.entry("com.bifos.assistant.connector.connection.domain.ConnectorConnection", new Contract("connector_connection", "C")),
        Map.entry("com.bifos.assistant.connector.action.domain.ConnectorConversationGrant", new Contract("connector_conversation_grant", "C")),
        Map.entry("com.bifos.assistant.connector.action.domain.ConnectorToolGrant", new Contract("connector_tool_grant", "C")),
        Map.entry("com.bifos.assistant.crypto.domain.entity.UserDataKey", new Contract("user_data_key", "C")),
        Map.entry("com.bifos.assistant.followup.domain.FollowUp", new Contract("follow_up", "제외")),
        Map.entry("com.bifos.assistant.mcp.domain.AgentToken", new Contract("agent_token", "C")),
        Map.entry("com.bifos.assistant.memory.domain.entity.Memory", new Contract("memory", "C")),
        Map.entry("com.bifos.assistant.memory.domain.entity.MemoryCapture", new Contract("memory_capture", "C")),
        Map.entry("com.bifos.assistant.memory.domain.entity.MemoryCollection", new Contract("memory_collection", "C")),
        Map.entry("com.bifos.assistant.memory.domain.entity.MemoryEmbedding", new Contract("memory_embedding", "C+U")),
        Map.entry("com.bifos.assistant.memory.domain.entity.MemoryLink", new Contract("memory_link", "C+U")),
        Map.entry("com.bifos.assistant.notification.domain.Notification", new Contract("notification", "C")),
        Map.entry("com.bifos.assistant.orchestration.domain.HermesSessionBinding", new Contract("hermes_session_binding", "C")),
        Map.entry("com.bifos.assistant.people.domain.AllowedPerson", new Contract("allowed_person", "C+U")),
        Map.entry("com.bifos.assistant.proactive.autonomy.domain.AutonomyDecision", new Contract("proactive_autonomy_decision", "제외")),
        Map.entry("com.bifos.assistant.proactive.check.domain.ProactiveCheckFinding", new Contract("proactive_check_finding", "C")),
        Map.entry("com.bifos.assistant.proactive.check.domain.ProactiveCheckProblem", new Contract("proactive_check_problem", "C")),
        Map.entry("com.bifos.assistant.proactive.loop.domain.ProactiveLoopRun", new Contract("proactive_loop_run", "제외")),
        Map.entry("com.bifos.assistant.proactive.evaluation.domain.ValueEvaluation", new Contract("proactive_value_evaluation", "제외")),
        Map.entry("com.bifos.assistant.task.domain.Task", new Contract("task", "C+U")),
        Map.entry("com.bifos.assistant.task.domain.TaskRun", new Contract("task_run", "제외")),
        Map.entry("com.bifos.assistant.task.domain.TaskTrigger", new Contract("task_trigger", "C+U")),
        Map.entry("com.bifos.assistant.usage.execution.domain.ExecutionContextSource", new Contract("execution_context_source", "C")),
        Map.entry("com.bifos.assistant.usage.subagent.domain.SubagentUsageJob", new Contract("subagent_usage_job", "제외")),
        Map.entry("com.bifos.assistant.user.domain.AppUser", new Contract("app_user", "C+U")));


    static ArchRule rule() {
        return classes().should(new ArchCondition<JavaClass>("순수 저장 시각의 base와 업무 시각 제외 및 기존 Clock provider를 지킨다") {
            private final Map<String, Integer> counts = new HashMap<>();


            @Override
            public void init(Collection<JavaClass> allClasses) {
                counts.clear();
                ENTITIES.keySet().forEach(name -> counts.put(name, 0));
                List.of(CREATED, AUDITED, CONFIG, "provider").forEach(name -> counts.put(name, 0));
            }


            @Override
            public void check(JavaClass type, ConditionEvents events) {
                String name = type.getName();
                boolean created = name.equals(CREATED);
                boolean audited = name.equals(AUDITED);
                for (var entry : ENTITIES.entrySet()) {
                    if (name.equals(entry.getKey()) || (type.isAnnotatedWith(Table.class)
                        && type.getAnnotationOfType(Table.class).name().equals(entry.getValue().table()))) {
                        counts.merge(entry.getKey(), 1, Integer::sum);
                        checkEntity(type, entry.getKey(), entry.getValue(), events);
                    }
                }
                if (type.isAnnotatedWith(Entity.class)) {
                    boolean hasCreation = type.getAllFields().stream().anyMatch(field -> field.isAnnotatedWith(Column.class)
                        && field.getAnnotationOfType(Column.class).name().equals("created_at"));
                    valid(type, !hasCreation || ENTITIES.containsKey(name), "created_at 판정 표에서 빠진 엔티티", events);
                    valid(type, !type.isAssignableTo(CREATED) || ENTITIES.containsKey(name), "판정 밖의 base 상속", events);
                }
                if (created || audited) {
                    counts.merge(name, 1, Integer::sum);
                    valid(type, type.isAnnotatedWith(MappedSuperclass.class), "MappedSuperclass 누락", events);
                    valid(type, type.getFields().size() == 1, "base는 감사 시각 한 필드만 소유한다", events);
                    if (audited) {
                        valid(type, type.getRawSuperclass().map(parent -> parent.getName().equals(CREATED)).orElse(false), "변경 base의 상속", events);
                    }
                }
                valid(type, !type.isAssignableTo(Auditable.class), "Auditable 구현 또는 상속 금지", events);
                if (type.isAnnotatedWith(EntityListeners.class)) {
                    var listeners = Arrays.asList(type.getAnnotationOfType(EntityListeners.class).value());
                    valid(type, created ? listeners.equals(List.of(AuditingEntityListener.class))
                        : !listeners.contains(AuditingEntityListener.class), "AuditingEntityListener 선언 위치", events);
                } else {
                    valid(type, !created, "생성 base의 listener 누락", events);
                }
                for (JavaField field : type.getFields()) {
                    boolean c = created && field.getName().equals("createdAt");
                    boolean u = audited && field.getName().equals("updatedAt");
                    valid(type, field.isAnnotatedWith(CreatedDate.class) == c && field.isAnnotatedWith(LastModifiedDate.class) == u
                        && !field.isAnnotatedWith(CreatedBy.class) && !field.isAnnotatedWith(LastModifiedBy.class),
                        "허용하지 않은 auditing field " + field.getFullName(), events);
                    if (c || u) {
                        valid(type, field.getRawType().isEquivalentTo(Instant.class) && field.isAnnotatedWith(Column.class), "감사 시각 Instant/Column", events);
                        if (field.isAnnotatedWith(Column.class)) {
                            Column column = field.getAnnotationOfType(Column.class);
                            valid(type, column.name().equals(c ? "created_at" : "updated_at")
                                && (!c || (!column.nullable() && !column.updatable())), "감사 칼럼 매핑", events);
                        }
                    }
                }
                for (JavaMethod method : type.getMethods()) {
                    valid(type, !method.isAnnotatedWith(CreatedDate.class) && !method.isAnnotatedWith(LastModifiedDate.class)
                        && !method.isAnnotatedWith(CreatedBy.class) && !method.isAnnotatedWith(LastModifiedBy.class), "auditing getter 금지", events);
                    if (method.isAnnotatedWith(Bean.class)) {
                        Bean bean = method.getAnnotationOfType(Bean.class);
                        boolean named = Arrays.asList(bean.value()).contains("auditingDateTimeProvider")
                            || Arrays.asList(bean.name()).contains("auditingDateTimeProvider")
                            || (bean.value().length == 0 && bean.name().length == 0 && method.getName().equals("auditingDateTimeProvider"));
                        if (named) {
                            counts.merge("provider", 1, Integer::sum);
                            valid(type, name.equals(CONFIG) && method.getRawReturnType().isEquivalentTo(DateTimeProvider.class)
                                && method.getRawParameterTypes().size() == 1
                                && method.getRawParameterTypes().getFirst().isEquivalentTo(Clock.class), "provider", events);
                        }
                    }
                }
                if (name.equals(CONFIG) || type.isAnnotatedWith(EnableJpaAuditing.class)) {
                    counts.merge(CONFIG, 1, Integer::sum);
                    valid(type, name.equals(CONFIG) && type.isAnnotatedWith(Configuration.class)
                        && type.isAnnotatedWith(EnableJpaAuditing.class)
                        && type.getAnnotationOfType(EnableJpaAuditing.class).dateTimeProviderRef().equals("auditingDateTimeProvider")
                        && type.getAnnotationOfType(EnableJpaAuditing.class).auditorAwareRef().isEmpty(), "기존 auditing 설정과 providerRef", events);
                }
            }


            @Override
            public void finish(ConditionEvents events) {
                counts.forEach((name, count) -> {
                    if (count != 1) {
                        events.add(SimpleConditionEvent.violated(name, name + " 선택 수는 1이어야 한다: " + count));
                    }
                });
            }
        });
    }


    private static void checkEntity(JavaClass type, String expected, Contract contract, ConditionEvents events) {
        valid(type, type.getName().equals(expected) && type.isAnnotatedWith(Entity.class), "엔티티 이름·표 선택", events);
        var creation = type.getAllFields().stream().filter(field -> field.isAnnotatedWith(Column.class)
            && field.getAnnotationOfType(Column.class).name().equals("created_at")).toList();
        valid(type, creation.size() == 1 && creation.getFirst().getRawType().isEquivalentTo(Instant.class), "created_at Instant 매핑 누락 또는 중복", events);
        String parent = type.getRawSuperclass().map(JavaClass::getName).orElse("");
        String wanted = contract.policy().equals("C+U") ? AUDITED : CREATED;
        valid(type, contract.policy().equals("제외") ? !type.isAssignableTo(CREATED) : parent.equals(wanted), "전수 판정의 base 상속", events);
    }


    private static void valid(JavaClass type, boolean condition, String detail, ConditionEvents events) {
        if (!condition) {
            events.add(SimpleConditionEvent.violated(type, type.getName() + ": " + detail));
        }
    }
}
