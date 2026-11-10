/**
 * KTX 계열과 SRT 가 서는 역의 이름 표다. 코레일의 역 목록 화면을 읽지 않고 이 표로만 받는다.
 * 비슷한 이름을 짐작하지 않는다. 끝의 「역」 만 떼고 표와 정확히 같아야 한다(spec DECISION21).
 *
 * 줄은 경부·경부고속, 호남·전라, 경전, 강릉, 중앙·중부내륙, 수서평택(SRT) 차례다.
 * 출처: 위키백과 「한국고속철도」 노선도와 노선별 정차역 안내(2026-11-10 확인). 코레일의 역 이름과 다르거나
 * 빠진 역이 있으면 사용자의 「실제 계정으로 확인하기」 에서 맞춘다. 표에 없는 역은 조회하지 못한다.
 */
export const STATIONS: ReadonlySet<string> = new Set(
  `
  행신 서울 영등포 광명 수원 천안아산 오송 대전 김천구미 동대구 서대구 경주 경산 밀양 포항 구포 울산 부산
  용산 서대전 공주 계룡 논산 익산 김제 정읍 장성 광주송정 나주 목포 전주 남원 곡성 구례구 순천 여천 여수엑스포
  진영 창원중앙 창원 마산 진주
  청량리 상봉 양평 만종 횡성 둔내 평창 진부(오대산) 강릉 정동진 묵호 동해
  서원주 원주 제천 단양 풍기 영주 안동 의성 영천 태화강 부전 판교 부발 가남 감곡장호원 앙성온천 충주
  수서 동탄 평택지제
  `
    .trim()
    .split(/\s+/),
);

/** 사람이 쓴 역 이름을 표의 이름으로 바꾼다. 끝의 「역」 하나만 떼고, 표에 없으면 `null` 이다. */
export function stationName(raw: string): string | null {
  const trimmed = raw.trim();
  const name =
    trimmed.endsWith("역") && STATIONS.has(trimmed.slice(0, -1))
      ? trimmed.slice(0, -1)
      : trimmed;
  return STATIONS.has(name) ? name : null;
}
