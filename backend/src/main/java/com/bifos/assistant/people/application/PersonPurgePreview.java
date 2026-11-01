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
 * <p>아무것도 쓰지 않는다. 실제로 지우는 진입점은 뒤 단계에서 연다. 지울 범위의 표와 칸이 이 목록과 어긋나지
 * 않도록 질의는 실제 표에 대해 돈다.
 */
@Service
@RequiredArgsConstructor
public class PersonPurgePreview {

    /** 표, 사용자 칸, 추가 조건, 정리 때 하는 일이다. 추가 조건은 구현이 정한 고정 문자열이다. */
    private record Target(String table, String userColumn, String extraWhere, Action action) {
    }


    private static final List<Target> TARGETS = List.of(
        new Target("conversation", "user_id", "", Action.DELETE),
        new Target("chat_attachment", "uploaded_by_user_id", "", Action.DELETE),
        new Target("chat_artifact", "vault_user_id", "", Action.DELETE),
        new Target("memory", "owner_user_id", " and scope = 'USER'", Action.DELETE),
        new Target("memory", "owner_user_id", " and scope = 'GROUP'", Action.TRANSFER),
        new Target("follow_up", "user_id", "", Action.DELETE),
        new Target("task", "owner_user_id", "", Action.DELETE),
        new Target("notification", "user_id", "", Action.DELETE),
        new Target("connector_connection", "user_id", "", Action.DELETE),
        new Target("user_browser", "user_id", "", Action.DELETE),
        new Target("user_data_key", "user_id", "", Action.DELETE),
        new Target("agent", "owner_user_id", " and deleted_at is null and visibility = 'PRIVATE'", Action.DELETE),
        new Target("agent", "owner_user_id", " and deleted_at is null and visibility = 'GROUP'", Action.TRANSFER));

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
        String sql = "select count(*) from " + target.table() + " where " + target.userColumn() + " = :userId" + target.extraWhere();
        Number n = (Number)entityManager.createNativeQuery(sql).setParameter("userId", userId).getSingleResult();
        return n.longValue();
    }
}
