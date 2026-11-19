# 목록 새로 읽기와 단계별 모델 표시 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 가짜 Hermes 가 `refresh=true` 에만 새 모델을 준다 | `cd backend && ./gradlew test --tests '*GroupModelCatalogServiceTest'` | 다시 읽기 전에는 그룹 목록에 새 모델이 없고 뒤에는 있다 | 로컬 H2 테스트 | worker | 없음 | PR 전 |
| REQ1 | 관리자로 연 `/admin/models` | `cd web && pnpm test:browser admin-model-tiers admin-model-hidden` | 「목록 새로 읽기」 뒤 단계별 모델 선택지와 숨김 후보가 바뀌고, 읽는 동안 단추가 막히며, 뒤에 다시 읽은 시각이 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ2 | `MEMBER` 역할 요청, 고정 시계 | `cd backend && ./gradlew test --tests '*ModelCatalogRefreshRouteTest' --tests '*ModelOptionsServiceTest'` | `MEMBER` 는 `FORBIDDEN` 이고 Hermes 호출이 없다. 30초 안의 재요청은 Hermes 를 부르지 않는다. 실패는 예외 없이 실패로 답한다 | 로컬 H2 테스트 | worker | 없음 | PR 전 |
| REQ2 | 다시 읽기가 `FAILED`, `REUSED` 로 답한다 | `cd web && pnpm test:browser admin-model-tiers` | `FAILED` 뒤 선택지가 그대로이고 실패 안내가 보인다. `REUSED` 는 새로 묻지 않았다는 안내와 시각이 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ3 | 다섯 단계를 채운 그룹 | `cd web && pnpm test:browser model-choice` | 세로 목록의 줄마다 모델과 강도, 고른 줄의 체크 표시가 보인다. 줄을 누르거나 방향키로 옮기면 저장된다. 폭 360px 에서 단계 이름이 잘리지 않는다. 닫힌 단추는 단계 이름만 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ4 | `MEMBER` 역할 요청자 | `cd backend && ./gradlew test --tests '*ModelTierServiceTest'` | 단계마다 `model`, `reasoningEffort` 가 있고 `provider` 는 null 이다 | 로컬 H2 테스트 | worker | 없음 | PR 전 |
| REQ4 | `MEMBER` 응답 모양의 단계 목록 | `cd web && pnpm test:browser model-choice` | 목록에 모델과 강도가 보이고 provider 이름은 보이지 않는다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ5 | 채운 단계와 비운 단계가 섞인 그룹 | `cd web && pnpm test:browser tasks` | 채운 단계는 단계 이름과 모델, 강도로, 비운 단계와 「기본값」 은 지금 문구로 보인다 | 로컬 브라우저 테스트 | worker | 없음 | PR 전 |
| REQ1 | 운영에 배포된 PR | 스펙의 「배포 뒤 확인」 세 항목 | 「목록 새로 읽기」 뒤 gpt-6.2 계열이 보이는지와, 보이면 그 단계로 한 번 왕복한 실행의 실제 모델, `MEMBER` 사용자의 휴대폰 화면 | 운영 | 코디네이터 | 배포 | PR 뒤 |
