package com.bifos.assistant.quality.application;

import com.bifos.assistant.quality.application.dto.OpenedEval;
import com.bifos.assistant.chat.artifact.domain.MediaPathMask;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.quality.application.dto.EvalBundleView;
import com.bifos.assistant.quality.application.dto.EvalMessageView;
import com.bifos.assistant.quality.application.dto.EvalPage;
import com.bifos.assistant.quality.application.dto.EvalView;
import com.bifos.assistant.quality.application.dto.StartedEval;
import com.bifos.assistant.quality.domain.EvalBundle;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 묶음 평가를 시작하고 읽는다(docs/features/agent-quality/design.md 의 「평가 실행 API」). 관리자인지는 부르는 쪽이 먼저 확인한다.
 *
 * <p>그룹에 도는 평가는 하나다. 도는 평가가 있으면 새로 만들지 않고 그 줄을 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class EvalService {

    static final int PAGE_SIZE = 20;

    private final EvaluationUserService evaluationUsers;
    private final EvalBundles bundles;
    private final EvalRuns runs;
    private final EvalRunner runner;
    private final QualityRunRepository rows;
    private final ModelTierService modelTiers;
    private final ChatService chat;


    public List<EvalBundleView> bundles(CurrentUser admin) {
        return bundles.all().stream()
            .map(bundle -> new EvalBundleView(bundle.id(), bundle.title(), bundle.questions().size(), bundle.timeoutMinutes()))
            .toList();
    }


    /**
     * 평가를 시작한다. 새로 만든 줄이면 요청 밖에서 문항을 차례로 돌린다.
     *
     * @param bundleIds 고른 묶음. 비면 전체
     * @param tier 고른 모델 단계. 없으면 평가 사용자에게 걸리는 기본 단계(사용자 기본, 없으면 그룹 기본)를 풀어 결과에 적는다
     * @throws ApiException 평가 사용자가 없으면 {@code EVALUATION_USER_MISSING}, 기본 에이전트가 없으면 {@code EVALUATION_USER_NOT_READY}, 모르는 묶음이면
     * {@code QUALITY_EVAL_BUNDLE_UNKNOWN}
     */
    public StartedEval start(CurrentUser admin, List<String> bundleIds, ModelTier tier) {
        AppUser evaluation = evaluationUsers.find(admin.groupId())
            .orElseThrow(() -> new ApiException(ErrorCode.EVALUATION_USER_MISSING, "prepare the evaluation user first"));
        List<EvalBundle> chosen = chosen(bundleIds);
        CurrentUser user = EvaluationUserService.asCurrentUser(evaluation);
        if (evaluationUsers.primaryAgent(user).isEmpty()) {
            // 기본 에이전트가 없으면 문항을 하나도 보내지 못한다. 줄을 만들지 않고 준비부터 하게 한다.
            throw new ApiException(ErrorCode.EVALUATION_USER_NOT_READY, "finish preparing the evaluation user first");
        }
        String usedTier = tier != null ? tier.name() : defaultTier(user);
        int itemCount = chosen.stream().mapToInt(bundle -> bundle.questions().size()).sum();
        OpenedEval opened = runs.open(evaluation.id(), chosen.stream().map(EvalBundle::id).toList(), usedTier, itemCount, admin.id());
        if (opened.created()) {
            runner.launch(opened.run().id(), evaluation.id(), chosen, tier);
        }
        return new StartedEval(EvalView.of(opened.run()), opened.created());
    }


    public EvalView get(CurrentUser admin, UUID publicId) {
        return EvalView.of(require(admin, publicId));
    }


    /**
     * 최근 순으로 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽의 {@code nextCursor}. 처음이면 null
     */
    public EvalPage list(CurrentUser admin, String cursor) {
        PageRequest page = PageRequest.of(0, PAGE_SIZE + 1);
        List<QualityRun> found = cursor == null || cursor.isBlank()
            ? rows.findByGroupIdAndKindOrderByIdDesc(admin.groupId(), QualityRunKind.EVAL, page)
            : rows.findByGroupIdAndKindAndIdLessThanOrderByIdDesc(admin.groupId(), QualityRunKind.EVAL, require(admin, parse(cursor)).id(), page);
        boolean more = found.size() > PAGE_SIZE;
        List<QualityRun> shown = more ? found.subList(0, PAGE_SIZE) : found;
        return new EvalPage(shown.stream().map(EvalView::of).toList(), more ? shown.getLast().publicId().toString() : null);
    }


    /**
     * 평가 전용 사용자의 대화 메시지를 읽는다. 남겨 둔 실패 대화와 사람 비교 문항의 답을 관리자가 본다. 다른 사용자의 대화는 없는 것과 같다.
     *
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}
     */
    public List<EvalMessageView> messages(CurrentUser admin, UUID conversationPublicId) {
        AppUser evaluation = evaluationUsers.find(admin.groupId())
            .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "no such conversation"));
        CurrentUser user = EvaluationUserService.asCurrentUser(evaluation);
        Conversation conversation = chat.conversationOf(user, conversationPublicId);
        List<EvalMessageView> views = new ArrayList<>();
        for (ChatMessage message : chat.history(user, conversation.id())) {
            // 사용자 화면과 같이 답 본문의 Hermes 그림 경로를 가린다(ADR-20261119).
            String content = message.role() == MessageRole.ASSISTANT ? MediaPathMask.strip(message.content()) : message.content();
            views.add(new EvalMessageView(message.role().name(), content, message.executionId(), message.createdAt()));
        }
        return views;
    }


    private List<EvalBundle> chosen(List<String> bundleIds) {
        if (bundleIds == null || bundleIds.isEmpty()) {
            return bundles.all();
        }
        List<EvalBundle> chosen = new ArrayList<>();
        for (String id : new LinkedHashSet<>(bundleIds)) {
            chosen.add(bundles.find(id).orElseThrow(() -> new ApiException(ErrorCode.QUALITY_EVAL_BUNDLE_UNKNOWN, "no such eval bundle")));
        }
        return chosen;
    }


    /** 고르지 않았을 때 평가 사용자에게 걸리는 기본 단계다. 정해지지 않았으면 null 이다. */
    private String defaultTier(CurrentUser evaluation) {
        return evaluationUsers.primaryAgent(evaluation)
            .map(agent -> modelTiers.detachedChoice(evaluation, agent).tier())
            .map(ModelTier::name)
            .orElse(null);
    }


    private QualityRun require(CurrentUser admin, UUID publicId) {
        return rows.findByPublicId(publicId)
            .filter(run -> run.groupId().equals(admin.groupId()) && run.kind() == QualityRunKind.EVAL)
            .orElseThrow(() -> new ApiException(ErrorCode.QUALITY_EVAL_NOT_FOUND, "no such eval"));
    }


    private static UUID parse(String cursor) {
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid cursor");
        }
    }
}
