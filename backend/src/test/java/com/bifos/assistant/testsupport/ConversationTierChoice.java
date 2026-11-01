package com.bifos.assistant.testsupport;

import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.modelchoice.application.ModelSettingStore;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSettingId;
import com.bifos.assistant.chat.modelchoice.infra.GroupSettingRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 그룹 설정의 칸 하나가 정한 provider, 모델, effort 를 가리키게 하고 대화가 그 칸을 고르게 한다.
 *
 * <p>대화의 모델 선택은 칸으로만 정한다. 실행이 어떤 값을 싣는지 보려는 검사는 그 값을 가리키는 칸을 만들어 고른다.
 * 칸 고르기 API 는 Hermes 목록에 없는 모델을 거절하므로 저장소로 직접 고른다. 실행 경로는 목록에 없어도 경고만
 * 남기고 보낸다.
 *
 * <p>설정 줄은 그룹 하나에 하나라 같은 그룹을 쓰는 다른 검사에 남는다. 검사가 끝나면 {@link #clear} 로 지운다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConversationTierChoice {

    /** 그룹의 그 칸이 {@code choice} 를 가리키게 한다. 다른 칸과 숨김은 그대로 둔다. */
    public static void define(ModelSettingStore settings, Long groupId, ModelTier tier, ModelChoice choice) {
        ModelSetting current = settings.read(groupId);
        List<ModelSetting.Stop> tiers = new ArrayList<>(current.tiers());
        tiers.set(tier.ordinal(), new ModelSetting.Stop(tier, choice.provider(), choice.model(), choice.reasoningEffort()));
        settings.saveTiers(groupId, tiers, current.defaultTier());
    }


    /** 대화가 그 칸을 고르게 한다. 그 대화의 주인이 {@code userId} 여야 한다. */
    public static void choose(
        ConversationRepository conversations,
        TransactionTemplate transactions,
        Long conversationId,
        Long userId,
        ModelTier tier) {
        Integer changed = transactions.execute(
            status -> conversations.chooseModelTierIfActive(conversationId, userId, ModelSelectionMode.TIER, tier));
        if (changed == null || changed != 1) {
            throw new IllegalStateException("conversation " + conversationId + " was not changed");
        }
    }


    /** 칸을 {@code choice} 로 정의하고 대화가 그 칸을 고르게 한다. */
    public static void defineAndChoose(
        ModelSettingStore settings,
        ConversationRepository conversations,
        TransactionTemplate transactions,
        Long groupId,
        Long userId,
        Long conversationId,
        ModelTier tier,
        ModelChoice choice) {
        define(settings, groupId, tier, choice);
        choose(conversations, transactions, conversationId, userId, tier);
    }


    /** 그룹의 모델 설정 줄을 지운다. 줄이 없으면 아무것도 하지 않는다. */
    public static void clear(GroupSettingRepository groupSettings, TransactionTemplate transactions, Long groupId) {
        transactions.executeWithoutResult(
            status -> groupSettings.deleteById(new GroupSettingId(groupId, GroupSetting.MODEL)));
    }
}
