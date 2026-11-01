package com.bifos.assistant.people.application;

import com.bifos.assistant.people.application.dto.PurgePreview;
import com.bifos.assistant.people.application.dto.PurgePreview.Action;
import com.bifos.assistant.people.application.dto.PurgePreview.Item;
import com.bifos.assistant.people.domain.AllowedPerson;
import com.bifos.assistant.people.infra.AllowedPersonRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자를 정리하면 무엇이 얼마나 지워지는지 읽기만 해서 보인다(ADR-20261101 / user-purge).
 *
 * <p>아무것도 쓰지 않는다. 실제로 지우는 진입점은 뒤 단계에서 연다. 질의는 실제 표에 대해 돌아 칸 이름이 틀리면
 * 테스트가 잡는다. 세는 대상은 ADR 의 지울 범위 중 사용자 번호로 찾을 수 있는 표다. 대화는 아직 지우지 않은 줄만 센다.
 * 참조를 비우거나 줄을 지우는 `app_user` 외래 키 표(ADR 의 「app_user 를 가리키는 다른 표」)는 세지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PersonPurgePreview {

    /** 표, 그 사용자의 줄을 고르는 조건(`:userId` 바인딩), 정리 때 하는 일이다. 조건은 구현이 정한 고정 문자열이다. */
    private record Target(String table, String where, Action action) {
    }


    /** 그 사용자의 개인 기억 번호를 고르는 부분 질의다. 임베딩과 링크는 사용자 칸이 없어 이것으로 찾는다. */
    private static final String USER_MEMORY_IDS = "(select id from memory where owner_user_id = :userId and scope = 'USER')";

    private static final List<Target> TARGETS = List.of(
        new Target("conversation", "user_id = :userId and deleted_at is null", Action.DELETE),
        new Target("chat_attachment", "uploaded_by_user_id = :userId", Action.DELETE),
        new Target("chat_artifact", "vault_user_id = :userId", Action.DELETE),
        new Target("memory", "owner_user_id = :userId and scope = 'USER'", Action.DELETE),
        new Target("memory_embedding", "memory_id in " + USER_MEMORY_IDS, Action.DELETE),
        new Target("memory_link", "from_memory_id in " + USER_MEMORY_IDS + " or to_memory_id in " + USER_MEMORY_IDS,
            Action.DELETE),
        new Target("memory_revision", "owner_user_id = :userId", Action.DELETE),
        new Target("follow_up", "user_id = :userId", Action.DELETE),
        new Target("task", "owner_user_id = :userId", Action.DELETE),
        new Target("task_run", "owner_user_id = :userId", Action.DELETE),
        new Target("notification", "user_id = :userId", Action.DELETE),
        new Target("attention_event", "user_id = :userId", Action.DELETE),
        new Target("attention_control", "user_id = :userId", Action.DELETE),
        new Target("user_autonomy_preference", "user_id = :userId", Action.DELETE),
        new Target("connector_connection", "user_id = :userId", Action.DELETE),
        new Target("connector_tool_grant", "user_id = :userId", Action.DELETE),
        new Target("user_browser", "user_id = :userId", Action.DELETE),
        new Target("user_data_key", "user_id = :userId", Action.DELETE),
        new Target("agent", "owner_user_id = :userId and deleted_at is null and visibility = 'PRIVATE'", Action.DELETE),
        new Target("agent", "owner_user_id = :userId and deleted_at is null and visibility = 'GROUP'", Action.TRANSFER));

    private final AllowedPersonRepository people;
    private final AppUserRepository users;
    private final EntityManager entityManager;


    /**
     * 허용 목록의 한 사람이 가진 데이터를 표마다 세어 준다. 로그인한 적이 없어 사용자가 없으면 빈 목록이다.
     *
     * @throws ApiException 그 사람이 없으면 PERSON_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public PurgePreview preview(Long personId) {
        String email = people.findEmailById(personId)
            .orElseThrow(() -> new ApiException(ErrorCode.PERSON_NOT_FOUND, "no such person"));
        List<Long> userIds = users.findAllByNormalizedEmail(AllowedPerson.normalizeEmail(email)).stream()
            .map(AppUser::id)
            .sorted()
            .toList();
        List<Item> items = new ArrayList<>();
        for (Target target : TARGETS) {
            long rows = 0;
            for (Long userId : userIds) {
                rows += count(target, userId);
            }
            items.add(new Item(target.table(), target.action(), rows));
        }
        return new PurgePreview(personId, userIds.isEmpty() ? List.of() : List.copyOf(items));
    }


    private long count(Target target, Long userId) {
        String sql = "select count(*) from " + target.table() + " where " + target.where();
        Number n = (Number)entityManager.createNativeQuery(sql).setParameter("userId", userId).getSingleResult();
        return n.longValue();
    }
}
