package com.bifos.assistant.context;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 결과 항목 하나의 출처 머리줄과 신선도를 만든다(ADR-071).
 *
 * <p>머리줄의 칸과 시각 형식, {@code FAILED} 일 때 붙는 오류 칸과 복구 안내, 신선도 안내 줄은 이 클래스가 갖는다. 자동 turn 과
 * 다시 전달이 같은 함수로 머리줄을 만들어, 몇 시간 뒤에 다시 전한 결과도 같은 형식으로 오래됐다고 알린다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResultHeader {

    /** 모델이 사용자에게 말할 시각이라 가족이 사는 곳의 시각으로 적는다. */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /** 끝난 시각이 비어 있을 때 머리줄에 적는 글이다. */
    private static final String UNKNOWN_TIME = "모름";

    /**
     * 결과가 지금도 참이라고 볼 수 있는지다.
     *
     * @param asOf 결과가 끝난 시각. 비어 있으면 {@link ContextFreshness#UNKNOWN} 이다
     * @param now 묶음을 만든 시각
     * @param staleAfter 이보다 오래 지났으면 {@link ContextFreshness#STALE} 이다. 같으면 아직 {@link ContextFreshness#FRESH} 다
     */
    public static ContextFreshness freshnessOf(Instant asOf, Instant now, Duration staleAfter) {
        if (asOf == null) {
            return ContextFreshness.UNKNOWN;
        }
        return Duration.between(asOf, now).compareTo(staleAfter) > 0 ? ContextFreshness.STALE : ContextFreshness.FRESH;
    }

    /**
     * {@code [출처: <sourceLabel>, <fields>, 끝난 시각: <시각>]} 한 줄이다. 오래된 결과면 {@code ]} 앞에 신선도를 넣고 다음 줄에 안내를
     * 붙인다.
     *
     * @param fields 출처 뒤에 차례로 적을 {@code 이름: 값} 들
     * @param asOf 결과가 끝난 시각. 비어 있으면 「모름」 이다
     */
    public static String render(
            String sourceLabel, List<String> fields, Instant asOf, ContextFreshness freshness, Duration staleAfter) {
        StringBuilder header = new StringBuilder("[출처: ").append(sourceLabel);
        fields.forEach(field -> header.append(", ").append(field));
        header.append(", 끝난 시각: ").append(asOf == null ? UNKNOWN_TIME : TIME.format(asOf));
        if (freshness != ContextFreshness.STALE) {
            return header.append(']').toString();
        }
        return header.append(", 신선도: 오래됨]\n이 결과는 ")
                .append(durationLabel(staleAfter))
                .append("보다 전에 끝났다. 지금 상태와 다를 수 있다.")
                .toString();
    }

    /** 정시면 「N시간」, 정시가 아니면 「N분」 이다. 시간으로 내리면 30분이 「0시간」 이 된다. */
    private static String durationLabel(Duration staleAfter) {
        if (staleAfter.equals(Duration.ofHours(staleAfter.toHours()))) {
            return staleAfter.toHours() + "시간";
        }
        return staleAfter.toMinutes() + "분";
    }
}
