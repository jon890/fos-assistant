## ADR-20261007 / numbering-scheme: Flyway 는 UTC 시각 버전을 쓰고 ADR 은 결정 날짜와 슬러그로 구분한다

- **status**: `accepted`
- Date: 2026-10-07
- **대체된 부분**: 날짜 ADR 의 제목 머리를 `ADR-YYYYMMDD / 슬러그` 로 쓰는 것과, ADR 파일을 모듈의 `docs/adr/` 와 `archive/` 로 옮기는 것은 [ADR-20261009 / adr-per-module](ADR-20261009-adr-per-module.md) 이 정한다. 파일 이름과 식별자는 그대로 둔다.

### 결정

새 Flyway 마이그레이션은 `V<YYYYMMDDHHMMSS>__<설명>.sql` 로 만든다. UTC 작성 시각 14자리다.
이 결정의 PR 이 머지될 때 main 에 있는 숫자 버전은 이름과 내용을 유지한다.
운영과 시험에 `spring.flyway.out-of-order=true` 를 켠다.
서로 의존하는 마이그레이션은 한 PR 에 두고 의존 순서대로 시각을 정한다.

CI 는 main 대비 새 파일에 시각 형식과 유효한 날짜를 요구하고, 지금보다 1일 넘게 미래인 시각을 거절한다.
같은 버전이 있는지도 모든 파일에서 검사한다. 운영에 적용된 파일의 체크섬 검사는 계속 유지한다.
같은 초에 작성해 버전이 겹치면 적용하지 않은 새 파일만 시각을 다시 정한다.

새 ADR 은 `ADR-<YYYYMMDD>-<슬러그>.md` 이고 제목 머리는 `ADR-YYYYMMDD` 다.
날짜는 결정한 날이다. 같은 날은 슬러그로 구분하며 기존 숫자 ADR 은 그대로 둔다.
문서 참조는 `[ADR-YYYYMMDD / 슬러그](파일 경로)` 로 쓴다.
목록은 기존 번호순 뒤에 날짜순, 같은 날짜 안에서는 슬러그 사전순으로 둔다.
작성 규칙은 [ADR 목록](INDEX.md) 과 [마이그레이션 작성 규칙](../backend/schema/README.md) 이 갖는다.

### 배경

병렬 브랜치가 main 의 다음 번호를 미리 골라 Flyway 버전과 ADR 번호가 반복해서 겹쳤다.
번호를 옮긴 뒤 다시 합치고 CI 를 실행하느라 변경 내용과 무관한 대기가 늘었다.
시각과 날짜를 사용하면 각 브랜치에서 식별자를 정하고 합칠 때도 유지할 수 있다.

### 영향과 검증

낮은 버전의 마이그레이션이 나중에 적용될 수 있으므로 서로 다른 PR 의 SQL 은 독립이어야 한다.
out-of-order 는 의존 순서를 해결하지 않는다. 의존하는 SQL 을 한 PR 에 두는 규칙이 그 순서를 보장한다.
기존 파일을 다시 이름 붙이지 않아 Flyway 적용 기록과 기존 문서 링크를 유지한다.

실제 MySQL 검사는 높은 시각 버전을 먼저 적용한 뒤 낮은 시각 버전의 임시 SQL 을 추가해 적용과 검증을 확인한다.
임시 파일은 검사 뒤 지운다. 새 형식도 기존 마이그레이션 불변 검사와 정렬 규칙 검사를 그대로 받는다.

### 대안 기각

main 의 다음 번호로 계속 옮기는 방식은 병렬 작업의 합치기 순서에 따라 번호가 바뀌므로 기각한다.
out-of-order 를 끈 시각 버전은 낮은 버전의 브랜치가 늦게 머지되면 적용되지 않으므로 기각한다.

Flyway 공식 문서도 충돌을 줄이기 위한 시각 버전을 설명하며,
[버전 마이그레이션](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations) 과
[out-of-order 설정](https://documentation.red-gate.com/fd/flyway-out-of-order-setting-277579015.html) 이 동작 근거다.
