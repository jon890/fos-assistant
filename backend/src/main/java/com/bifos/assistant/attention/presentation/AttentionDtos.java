package com.bifos.assistant.attention.presentation;

import com.bifos.assistant.attention.application.model.AttentionCard;
import com.bifos.assistant.attention.application.model.AttentionExecutionRef;
import com.bifos.assistant.attention.application.model.AttentionFollowUpRef;
import com.bifos.assistant.attention.application.model.AttentionItem;
import com.bifos.assistant.attention.application.model.AttentionMetric;
import com.bifos.assistant.attention.application.model.AttentionSignal;
import com.bifos.assistant.attention.application.model.AttentionSourceRef;
import com.bifos.assistant.attention.application.model.AttentionView;
import com.bifos.assistant.attention.application.model.AttentionWhy;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 먼저 알리기 경로의 응답 모양이다. 계약은 {@code docs/backend/attention.md} 의 「API」 가 갖는다.
 *
 * <p>실행의 오류 코드, 모델, 토큰, 금액 칸을 두지 않는다. 일반 경로라 역할과 상관없이 뺀다(ADR-063).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AttentionDtos {

    /** 카드 열쇠를 응답의 소문자 글로 바꾼다. */
    public static String cardKeyText(CardKey key) {
        return key.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 요청의 카드 글을 카드 열쇠로 바꾼다. {@code failures}, {@code needs_me}, {@code delegated}, {@code continue} 만 받는다.
     *
     * @return 모르는 글이거나 null 이면 null
     */
    public static CardKey cardOf(String text) {
        return text == null
                ? null
                : Arrays.stream(CardKey.values())
                        .filter(key -> cardKeyText(key).equals(text))
                        .findFirst()
                        .orElse(null);
    }

    /**
     * 요청의 사건 글을 사건 종류로 바꾼다. 이름이 맞는지만 보고, 받는 종류인지는 서비스가 본다.
     *
     * @return {@link AttentionEventType} 이름이 아니면 null
     */
    public static AttentionEventType eventTypeOf(String text) {
        return text == null
                ? null
                : Arrays.stream(AttentionEventType.values())
                        .filter(type -> type.name().equals(text))
                        .findFirst()
                        .orElse(null);
    }

    /** @param card 카드 열쇠의 소문자 글 */
    public record HideRequest(String card, String itemKey, String stateKey) {}

    /** @param until 지금보다 뒤이고 {@code snooze-max} 안이어야 한다 */
    public record SnoozeRequest(String card, String itemKey, Instant until) {}

    public record RestoreRequest(String card, String itemKey) {}

    /** @param type {@code OPENED} 나 {@code ACTED} */
    public record EventRequest(String itemKey, String stateKey, String type) {}

    /**
     * 관리자 지표다. 제목, 항목 열쇠, 사용자 번호를 싣지 않는다.
     *
     * @param days 센 기간의 일 수
     * @param rows {@code trigger} 선언 순서다. 보인 항목이 없는 {@code trigger} 는 줄이 없다
     */
    public record MetricsResponse(int days, List<MetricRow> rows) {}

    /** 뜻은 {@code docs/backend/attention.md} 의 「지표」 가 갖는다. */
    public record MetricRow(
            String trigger,
            long shown,
            long hidden,
            long snoozed,
            long acted,
            long nowShown,
            long nowHiddenWithoutAction,
            long staleShown,
            Long medianSecondsToFirstAction) {

        public static MetricRow from(AttentionMetric metric) {
            return new MetricRow(
                    metric.trigger().name(),
                    metric.shown(),
                    metric.hidden(),
                    metric.snoozed(),
                    metric.acted(),
                    metric.nowShown(),
                    metric.nowHiddenWithoutAction(),
                    metric.staleShown(),
                    metric.medianSecondsToFirstAction());
        }
    }

    /**
     * @param nowCount 카드 {@code nowCount} 의 합
     */
    public record ViewResponse(Instant readAt, int nowCount, List<CardView> cards) {

        public static ViewResponse from(AttentionView view) {
            return new ViewResponse(
                    view.readAt(),
                    view.nowCount(),
                    view.cards().stream().map(CardView::from).toList());
        }
    }

    /**
     * @param key 카드 열쇠의 소문자 글({@code failures}, {@code needs_me}, {@code delegated}, {@code continue})
     * @param status {@code OK} 나 {@code UNAVAILABLE}
     * @param nowCount 상한으로 자르기 전 이 카드의 {@code NOW} 항목 수
     * @param moreCount 상한을 넘어 빠진 항목 수
     */
    public record CardView(String key, String status, int nowCount, int moreCount, List<ItemView> items) {

        static CardView from(AttentionCard card) {
            return new CardView(
                    cardKeyText(card.key()),
                    card.status().name(),
                    card.nowCount(),
                    card.moreCount(),
                    card.items().stream().map(ItemView::from).toList());
        }
    }

    /**
     * 항목 하나다. 해당하지 않는 {@code execution}, {@code actionId}, {@code followUp} 은 null 로 낸다. 화면이 {@code itemKey}
     * 를 잘라 식별자를 얻지 않게 하려는 것이다.
     *
     * @param attention {@code NOW} 나 {@code LATER}
     * @param title 평문으로 그린다
     */
    public record ItemView(
            String itemKey,
            String stateKey,
            String attention,
            String title,
            UUID conversationId,
            String agentName,
            Instant at,
            WhyView why,
            ExecutionView execution,
            UUID actionId,
            FollowUpView followUp) {

        static ItemView from(AttentionItem item) {
            return new ItemView(
                    item.itemKey(),
                    item.stateKey(),
                    item.level().name(),
                    item.title(),
                    item.conversationId(),
                    item.agentName(),
                    item.at(),
                    WhyView.from(item.why()),
                    ExecutionView.from(item.execution()),
                    item.actionId(),
                    FollowUpView.from(item.followUp()));
        }
    }

    /** @param status 실행 상태 이름 */
    public record ExecutionView(Long id, String status) {

        static ExecutionView from(AttentionExecutionRef execution) {
            return execution == null ? null : new ExecutionView(execution.id(), execution.status());
        }
    }

    /** @param id 할 일의 공개 식별자 */
    public record FollowUpView(UUID id, Instant dueAt, boolean waiting, boolean proposed) {

        static FollowUpView from(AttentionFollowUpRef followUp) {
            return followUp == null
                    ? null
                    : new FollowUpView(followUp.id(), followUp.dueAt(), followUp.waiting(), followUp.proposed());
        }
    }

    public record WhyView(String trigger, List<String> signals, String confidence, List<SourceView> sources) {

        static WhyView from(AttentionWhy why) {
            return new WhyView(
                    why.trigger().name(),
                    why.signals().stream().map(AttentionSignal::name).toList(),
                    why.confidence().name(),
                    why.sources().stream().map(SourceView::from).toList());
        }
    }

    public record SourceView(String source, String ref, Instant asOf) {

        static SourceView from(AttentionSourceRef source) {
            return new SourceView(source.source(), source.ref(), source.asOf());
        }
    }

    /** @param nowCount {@code GET /api/v1/attention} 의 {@code nowCount} 와 같은 수 */
    public record SummaryResponse(int nowCount) {}
}
