package com.bifos.assistant.chat.modelchoice.application.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 모델 목록을 Hermes 에 다시 물은 결과다. profile 하나의 결과와 그룹 전체로 모은 결과가 같은 모양을 쓴다.
 *
 * @param status 이번 요청에서 실제로 다시 물었는지와 그 성패
 * @param refreshedAt 마지막으로 다시 읽는 데 성공한 시각. 한 번도 성공하지 못했으면 null 이다. 그룹 결과에서는 profile
 * 들의 시각 가운데 가장 이른 것이고, 성공한 적 없는 profile 이 하나라도 있으면 null 이다
 */
public record CatalogRefresh(Status status, Instant refreshedAt) {

    /** 다시 읽기의 결과다. */
    public enum Status {
        /** 이번 요청에서 Hermes 에 다시 물어 새 목록을 받았다. */
        REFRESHED,
        /** 최소 간격 안이라 새로 묻지 않았다. 앞 시도가 아직 도는 중일 때도 여기 든다. */
        REUSED,
        /** 다시 읽지 못했다. 최소 간격 안에 끝난 앞 시도가 실패였을 때도 여기 든다. */
        FAILED
    }


    /**
     * profile 마다의 결과를 그룹 하나의 결과로 모은다.
     *
     * <p>하나라도 실패면 실패이고, 실패 없이 하나라도 새로 묻지 않았으면 새로 묻지 않은 것이다. 모든 profile 을 이번에
     * 물었을 때만 「방금 다시 읽었다」 고 말할 수 있다. 물을 profile 이 없으면 다시 읽은 것으로 본다.
     */
    public static CatalogRefresh combine(List<CatalogRefresh> profiles) {
        Status status = Status.REFRESHED;
        if (profiles.stream().anyMatch(it -> it.status() == Status.FAILED)) {
            status = Status.FAILED;
        } else if (profiles.stream().anyMatch(it -> it.status() == Status.REUSED)) {
            status = Status.REUSED;
        }
        Instant oldest = profiles.stream().anyMatch(it -> it.refreshedAt() == null)
            ? null
            : profiles.stream().map(CatalogRefresh::refreshedAt).min(Comparator.naturalOrder()).orElse(null);
        return new CatalogRefresh(status, oldest);
    }
}
