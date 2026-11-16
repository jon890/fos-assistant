# PR1 사람을 기다리는 동안 브라우저 유지 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | `RUNNING` 브라우저, 마지막 활동 15분 전, 그 사용자의 열린 넘기기(대화 있음, 이번 켜기 이후) | 점검(`UserBrowserSweeper.sweep`)을 돌린다 | 끄지 않는다 | 로컬 H2 스프링 테스트와 단위 테스트(`UserBrowserSweeperTest`, `ConnectorBrowserWaitDemandTest`) | worker | 없음 | PR 전 |
| REQ1 | 위와 같되 마지막 활동 31분 전 | 점검을 돌린다 | 끈다 | 같은 테스트 | worker | 없음 | PR 전 |
| REQ1 | 기다림이 없고 마지막 활동 11분 전 | 점검을 돌린다 | 지금처럼 끈다 | 같은 테스트 | worker | 없음 | PR 전 |
| REQ1 | 브라우저 플러그인의 기다리는 승인 / 아닌 플러그인의 승인 / 시간이 지난 승인 / 대화 없는 넘기기 / 켜기 전 넘기기 | `waiting` 을 부른다 | 참 / 거짓 / 거짓 / 거짓 / 거짓 | `ConnectorBrowserWaitDemandTest`(H2) | worker | 없음 | PR 전 |
| REQ2 | 브라우저 플러그인의 `PENDING` 쓰기 줄, 브라우저 켜기가 실패하는 `FakeBrowserRuntime` | HTTP 승인을 부르고 줄과 피드백을 읽는다 | 409 `CONNECTOR_BROWSER_UNAVAILABLE`, 줄 `PENDING`, 피드백 사건 없음, 실행 호출 없음 | `ApprovalBrowserUnavailableTest`(스프링 통합, H2) | worker | 없음 | PR 전 |
| REQ2 | 같은 줄, 켜기 대역을 성공으로 바꿈 | 다시 승인한다 | 실행되고 줄이 `PENDING` 을 벗어난다 | 같은 테스트 | worker | 없음 | PR 전 |
| REQ2 | 쇼핑 구매 체크아웃 줄과 원고 저장 줄, 켜기 실패 | HTTP 승인 | 둘 다 409, 줄 `PENDING`, 오늘 구매 합계 그대로 | 같은 테스트 | worker | 없음 | PR 전 |
| REQ2 | 쇼핑의 상한 변경 줄, 켜기 실패 | HTTP 승인 | 브라우저를 켜지 않고 승인된다 | 같은 테스트 | worker | 없음 | PR 전 |
| REQ2 | 승인 카드 문구 | `connectorActionErrorMessage("CONNECTOR_BROWSER_UNAVAILABLE")` | 「브라우저를 켤 수 없어요. 잠시 뒤 다시 승인해 주세요.」 | `web/test/unit/connector-action.test.ts`(node test) | worker | 없음 | PR 전 |
| REQ1 | 운영 사용자 브라우저와 쇼핑 승인 카드 | 승인 카드를 띄우고 15분 동안 화면을 열지 않은 뒤 승인한다 | 브라우저가 켜진 채라 실행이 바로 시작된다 | 운영 배포 뒤 실계정 | 코디네이터 | 배포 | PR 뒤 |
