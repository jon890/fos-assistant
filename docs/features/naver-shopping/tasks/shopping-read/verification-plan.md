# 네이버 쇼핑 PR1 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 가짜 CDP 에 네이버 로그인 쿠키 둘이 있다, 없다, 중계 주소가 비었다 | `cd hermes/connectors/naver-shopping && bun run test` 의 확인 도구 테스트와 `python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'` | 쿠키가 있으면 connected 와 logged_in, 없으면 `NAVER_SHOPPING_LOGIN_REQUIRED`, 비면 `NAVER_SHOPPING_BROWSER_UNREACHABLE`. 계약 검사가 naver-shopping 을 통과하고 fields 가 비었다 | 로컬 Bun, Python | worker | 없음 | PR 전 |
| REQ2 | 합성 검색 상태 JSON(광고 포함 45개, 외부 주소, 거절 주소) | 파서 테스트와 탭 테스트 | 40개, 광고 표시, external 은 링크만, 거절 주소 항목 빠짐, 다음 페이지와 내리기 명령 없음, 정상이면 탭 닫힘 | 로컬 Bun, 로컬 Chrome | worker | 없음 | PR 전 |
| REQ3 | 합성 store 와 catalog 상태 JSON, 외부 주소 | 파서 테스트와 탭 테스트 | store 는 옵션과 배송비와 판매가, catalog 는 네이버페이 판매처 목록, 외부는 탭 없이 buyable false, 칸이 없으면 `NAVER_SHOPPING_PAGE_CHANGED` | 로컬 Bun | worker | 없음 | PR 전 |
| REQ4 | 가짜 CDP 의 로그인, 캡차, 기기 인증 화면 | 탭 테스트 | 각 코드로 끝나고 탭이 남으며, `Input.*` 가 없고 evaluate 식이 상수 집합 안이다 | 로컬 Bun | worker | 없음 | PR 전 |
| REQ5 | 틀린 선언 사본 셋 | `python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py'` | 영어 요지 없음, 한 낱말 이름, 두 플러그인 사이 같은 이름이 각각 실패로 잡힌다. 기존 플러그인은 통과 | 로컬 Python | worker | 없음 | PR 전 |
| REQ6 | 예약 작업 turn, 살펴보기 turn, 그 위임 자식, 맡긴 일의 결과 turn, 보통 대화 | phase 1 의 Gradle 테스트 | 사람이 없는 실행은 `ATTENDED_ONLY` 로 거절되고 승인 줄이 없다. 보통 대화와 그 위임 자식은 허용 | 로컬 Gradle(H2) | worker | 없음 | PR 전 |
| REQ2 | 배포한 플러그인, 사용자의 실제 네이버 계정과 「내 브라우저」 | 기능 파일의 「실제 계정으로 확인하기」 의 검색 | 검색이 실제 화면에서 결과를 낸다. 안 되면 `fields.ts` 를 맞춘다 | 운영(사용자가 직접 돈다) | 코디네이터 | 배포 | PR 뒤 |
| REQ3 | 배포한 플러그인, 사용자의 실제 네이버 계정과 「내 브라우저」 | 기능 파일의 「실제 계정으로 확인하기」 의 상세 | 상세가 실제 화면에서 결과를 낸다. 안 되면 `fields.ts` 를 맞춘다 | 운영(사용자가 직접 돈다) | 코디네이터 | 배포 | PR 뒤 |
| REQ1 | 카탈로그의 naver-shopping 항목 | 계약 검사의 로그인 주소 단언 | `owner_browser_login_url` 이 네이버 로그인 화면 주소다 | 로컬 Python | worker | 없음 | PR 전 |
| REQ6 | 승인 결과만 전하는 turn, 다시 전달, 매일 깨우기 turn, 사람이 연 루트와 사람이 없는 루트의 위임 자식, 루트 줄이 없는 실행, 카탈로그의 `attended_only` | `ChatTurnQuestionTest`, `TaskRunStarterTest` 의 깨우기 사례, `SessionOwnerResolverTest`, `HttpHermesConnectorClientTest` | 승인 결과 turn 과 다시 전달은 사람이 있는 실행, 깨우기 turn 은 표식이 적히고, 위임 자식은 루트를 따르며, 루트 줄이 없으면 사람이 없는 쪽, boolean 이 아닌 선언은 참 | 로컬 Gradle | worker | 없음 | PR 전 |
