# 발견·문제 후보 저장 끊기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 가짜 Hermes 가 발견 둘, 문제 후보 하나, 제안 하나를 담은 결과 블록 v3 를 낸다 | 수동 살펴보기를 돌리고 대화 답, 보고, 제안 줄, 두 테이블을 조회한다 | 답과 보고에 발견과 문제 후보가 그려지고 제안 줄이 하나 생기며 두 테이블에 줄이 없다 | backend H2 통합 테스트(`ProactiveCheckTurnTest`) | 없음 | PR 전 |
| REQ1 | 운영 코드 | `ProactiveCheckFindingRepository`, `ProactiveCheckProblemRepository` 의 저장 호출을 찾는다 | `LegacySuggestionBackfill` 밖에서 두 저장소를 쓰는 운영 코드가 없다 | 저장소 grep | 없음 | PR 전 |
| REQ2 | 같은 점검 대화에 열린 제안, 거절한 제안, 만료한 제안, 다른 대화의 제안, 보고가 있는 앞 살펴보기 | 다음 살펴보기의 Hermes 입력을 읽는다 | 열린 제안과 거절한 제안의 제목·상태·반응이 실리고 만료한 제안과 다른 대화의 제안은 없다. 보고의 발견 주제가 실린다. 두 절의 모델 글이 `<external-data>` 안에 있다. 각 절이 `digest-max-items` 줄을 넘지 않는다 | backend H2 통합 테스트 | 없음 | PR 전 |
| REQ7 | 보고에 주제 키와 원문을 남긴 앞 예약 살펴보기 | 같은 주제 키와 원문만 담은 예약 살펴보기를 돌리고, 이어 예약 살펴보기를 한 번 더 시작한다 | 둘째 살펴보기는 답과 보고 없이 끝나고, 셋째는 `UNREAD_REPORT` 로 건너뛰지 않고 돈다 | backend H2 통합 테스트 | 없음 | PR 전 |
| REQ7 | 보고에 받아들인 문제 키를 남긴 앞 살펴보기 | 같은 문제 키의 후보를 달라진 점 없이 다시 낸다 | 그 후보는 `DUPLICATE` 로 버려져 보고의 받아들인 키에 없다 | backend H2 통합 테스트 | 없음 | PR 전 |
| REQ7 | 옛 표에 발견과 문제 후보가 있고 보고에 두 칸이 없는 살펴보기 | 백필 마이그레이션을 돌린다 | 보고에 두 칸이 채워지고 다시 돌려도 같다 | backend 마이그레이션 단위 테스트(H2), CI 의 MySQL 마이그레이션 검사 | 없음 | PR 전 |
| REQ3 | 로그인한 사용자 | `GET /api/v1/chat/conversations/{id}/check-findings`, `PUT /api/v1/check-findings/{id}/reaction` 을 부른다 | 둘 다 404 | backend MockMvc | 없음 | PR 전 |
| REQ3 | 발견이 있는 점검 대화 | 브라우저로 점검 대화를 연다 | 답 아래에 「받아들임」, 「나중에」, 「관심 없음」 단추가 없다. 지금 화면의 제안 카드 반응 테스트가 통과한다 | 브라우저 테스트(`proactive-check`, `attention`) | 없음 | PR 전 |
| REQ4 | 읽지 않은 보고가 있는 점검 대화 | 예약 살펴보기를 시작하고, 보고를 연 뒤 다시 시작한다 | 처음은 `UNREAD_REPORT` 로 건너뛰고, 연 뒤에는 돈다 | 기존 backend 테스트(`ProactiveCheckStartTest`) | 없음 | PR 전 |
