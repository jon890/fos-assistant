package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.dto.AgentControlStatus;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트의 「내 브라우저」 조작을 켜고 끄는 두 스위치다(docs/features/agent-browser/design.md 의 「PR1 스위치」).
 *
 * <p>사용자 스위치는 {@code user_browser.agent_control_at}, 관리자 전체 스위치는 {@code group_setting} 의
 * {@link GroupSetting#BROWSER_AGENT_CONTROL} 이다. 줄이 없으면 허용이다. 줄을 읽지 못하거나 버전이 다르면 관리자가 끈 것으로 본다. 끄려던 값을 잃고
 * 허용하는 쪽으로 틀리지 않게 하려는 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserAgentControl {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 지금 쓰는 JSON 의 버전이다. 모양을 바꾸면 올리고 옛 버전을 읽는 길을 함께 둔다. */
    private static final int VERSION = 1;

    private final UserBrowserService browsers;
    private final UserBrowserRepository repository;
    private final GroupSettingStore settings;
    private final Clock clock;
    private final PlatformTransactionManager transactions;


    /** 저장하는 JSON 의 모양이다. 칸 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(int version, boolean enabled) {
    }


    /** 그 사용자의 판정이다. */
    public AgentControlStatus status(CurrentUser user) {
        if (!browsers.enabled()) {
            return AgentControlStatus.FEATURE_OFF;
        }
        if (!adminAllowed(user.groupId())) {
            return AgentControlStatus.ADMIN_OFF;
        }
        Optional<UserBrowser> browser = repository.findByUserId(user.id());
        if (browser.isEmpty()) {
            return AgentControlStatus.NO_BROWSER;
        }
        return browser.get().agentControlAt() == null ? AgentControlStatus.USER_OFF : AgentControlStatus.ALLOWED;
    }


    /** 사용자가 켰는가. 관리자 스위치와 기능 플래그는 보지 않는다. 화면이 스위치 모양을 그릴 때 쓴다. */
    public boolean userEnabled(Long userId) {
        return repository.findByUserId(userId).map(browser -> browser.agentControlAt() != null).orElse(false);
    }


    /**
     * 사용자 스위치를 바꾼다. 끄기는 늘 된다.
     *
     * @throws ApiException 브라우저가 없으면 {@code BROWSER_NOT_FOUND}, 관리자가 껐는데 켜려 하면 {@code BROWSER_AGENT_CONTROL_OFF}
     */
    public void setUser(CurrentUser user, boolean enabled) {
        if (repository.findByUserId(user.id()).isEmpty()) {
            throw new ApiException(ErrorCode.BROWSER_NOT_FOUND, "user browser not found");
        }
        if (!enabled) {
            write(() -> repository.disableAgentControl(user.id()));
            return;
        }
        if (!adminAllowed(user.groupId())) {
            throw new ApiException(ErrorCode.BROWSER_AGENT_CONTROL_OFF, "agent browser control is off for the group");
        }
        write(() -> repository.enableAgentControl(user.id(), clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }


    /** 관리자가 그룹 전체를 허용했는가. 줄이 없으면 허용, 읽지 못하면 끈 것이다. */
    public boolean adminAllowed(Long groupId) {
        return settings.read(groupId, GroupSetting.BROWSER_AGENT_CONTROL).map(BrowserAgentControl::parse).orElse(true);
    }


    /** 관리자 전체 스위치를 바꾼다. 사용자 스위치 값은 지우지 않는다. */
    public void setAdmin(Long groupId, boolean enabled) {
        settings.write(groupId, GroupSetting.BROWSER_AGENT_CONTROL, JSON.writeValueAsString(new Stored(VERSION, enabled)));
    }


    /** 칸 하나를 바꾸는 갱신을 짧은 트랜잭션으로 돌린다. {@code BrowserHumanHold} 와 같은 방식이다. */
    private void write(Runnable update) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> update.run());
    }


    private static boolean parse(String value) {
        try {
            Stored stored = JSON.readValue(value, Stored.class);
            return stored.version() == VERSION && stored.enabled();
        } catch (JacksonException ex) {
            log.warn("browser agent control setting is unreadable kind={}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
