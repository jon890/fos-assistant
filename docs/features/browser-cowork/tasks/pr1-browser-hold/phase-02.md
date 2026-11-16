# Phase 02. 승인할 때 브라우저를 켜지 못하면 승인을 쓰지 않는다

**Execution profile**: standard

## 목표

브라우저를 쓰는 플러그인의 승인 카드에서 승인을 누르면 승인 트랜잭션보다 먼저 브라우저를 켠다. 켜지 못하면 줄을 건드리지 않고 409 `CONNECTOR_BROWSER_UNAVAILABLE` 로 거절하고, 카드가 정해 둔 문구를 보인다.

**범위 외**: 사람 잠금 중 승인(PR3 이 같은 부품에 더한다).

## 컨텍스트

**요구사항**: REQ2

**근거 문서**: `docs/features/browser-cowork/spec.md`, `docs/features/browser-cowork/design.md` 의 「PR1 승인할 때 브라우저를 켜지 못하면(REQ2)」, `docs/features/connector-policy.md` 의 「승인이 필요한 호출」

- 승인 진입점은 `connector/approval/presentation/ConnectorActionController.java` 의 `approve` 하나이고, 원고 저장 승인(`ContentDraftSaveApproval.approve`)과 일반 승인(`ConnectorActionService.approve`)으로 갈린다. 일반 승인 안에서 상한 변경(`FinancialLimitChanges.owns`), 금융·구매(`ConnectorFinancialIntake.owns`), 일반 쓰기로 다시 갈린다.
- 상한 변경 줄은 판정에서 뺀다. 쇼핑의 상한 변경 줄은 `connectorId` 가 쇼핑이라 manifest 가 `owner_browser_env` 를 선언하지만 승인은 DB 만 바꾼다(설계 「PR1 승인할 때」). 그래서 `ConnectorActionService.approve` 의 상한 변경 분기 뒤에서 부르고, `ContentDraftSaveApproval.approve` 의 처음에서도 부른다.
- 켜기는 `browser/application/UserBrowserService#ensureRunning(Long userId)` 이다. 활동을 기록하지 않으므로 성공 뒤 `UserBrowserService#touch(userId)` 를 부른다(`browser/application/BrowserGateway.java` 의 방식).
- 실패 코드는 `ensureRunning` 의 Javadoc 과 `start` 를 본다: `BROWSER_CAPACITY`, `BROWSER_START_FAILED`, `BROWSER_BUSY`, `BROWSER_STOP_FAILED`, `BROWSER_NOT_FOUND` 는 바꾸고, `BROWSER_DISABLED` 는 그대로 던진다. `ApiException` 이 아닌 `RuntimeException`(프로필 디렉터리 준비 실패 등)도 `CONNECTOR_BROWSER_UNAVAILABLE` 로 바꾼다.
- 오류 코드는 `shared/error/ErrorCode.java` 의 `CONNECTOR_ACTION_NOT_PENDING(HttpStatus.CONFLICT)` 옆에 둔다.
- 웹의 문구 표는 `web/src/lib/connector-action-error.ts` 다. 승인 카드(`web/src/components/chat/approval-card.tsx` 의 `send`)는 실패한 `message` 를 보이고 줄을 다시 읽는다.

## 의도 메모

- 줄 잠금 전에 켜서 승인 트랜잭션이 30초까지 DB 연결과 사용자 행 잠금을 쥐지 않게 한다.
- 줄이 없거나 남의 것이거나 `PENDING` 이 아니거나 시간이 지났으면 아무것도 하지 않는다. 뒤의 기존 판정이 지금 응답(없음, 이미 처리됨, 만료)을 그대로 낸다. 남의 줄에서 브라우저를 켜면 안 된다.
- 하루 합계와 피드백 사건은 승인 트랜잭션 안에서만 남으므로, 그 앞에서 던지면 아무것도 남지 않는다.

## 작업 항목

### 1. `ErrorCode` 에 `CONNECTOR_BROWSER_UNAVAILABLE(HttpStatus.CONFLICT)`

한국어 주석으로 뜻(브라우저 플러그인 승인에서 브라우저를 켜지 못해 승인을 쓰지 않았다, 다시 승인할 수 있다)을 적는다.

### 2. `connector/action/application/ConnectorApprovalBrowser.java`(신규)

`@Component`. `void require(CurrentUser user, UUID actionId)`.
`ConnectorActionRepository.findByPublicId` 로 줄을 읽고, 주인이 그 사용자이고 `PENDING` 이고 `expiresAt` 이 지금보다 뒤일 때만 본다. `ConnectorCatalogCache#find(connectorId)` 가 있고(`ConnectorActionDetails` 는 빈이 아니다. 예외는 읽지 못한 것으로 본다) `ownerBrowser()` 가 참이면 `ensureRunning` 뒤 `touch`. 위 코드 목록의 `ApiException` 과 그 밖의 `RuntimeException` 은 `CONNECTOR_BROWSER_UNAVAILABLE` 로 바꿔 던지고 경고 로그에 원래 코드나 예외 종류만 남긴다. `BROWSER_DISABLED` 는 그대로 다시 던진다. manifest 를 읽지 못하면 켜지 않는다(뒤의 승인 판정이 실행하지 않고 끝낸다).

### 3. 두 승인 서비스에서 부르기

- `connector/approval/application/ConnectorActionService.java` 의 `approve(CurrentUser, UUID, GrantPeriod, boolean)`: 상한 변경 분기(`FinancialLimitChanges.owns`) 뒤, 금융·구매 분기 전에 `approvalBrowser.require(user, actionId)`.
- `connector/contentdraft/application/ContentDraftSaveApproval.java` 의 `approve(CurrentUser, UUID, GrantPeriod, boolean)` 처음에 같은 호출.

### 4. 웹 문구

`web/src/lib/connector-action-error.ts` 의 표에 `CONNECTOR_BROWSER_UNAVAILABLE: "브라우저를 켤 수 없어요. 잠시 뒤 다시 승인해 주세요."`. 서버 라우트가 코드를 넘기는 목록(`PASSED_CODES` 가 표에서 만들어지는지)을 확인하고, 따로 목록이면 더한다.

### 5. 문서

`docs/features/connector-policy.md` 「승인이 필요한 호출」 표에 행을 더한다: 브라우저 플러그인 줄을 승인할 때 브라우저를 켜지 못하면 409 `CONNECTOR_BROWSER_UNAVAILABLE`, 줄은 `PENDING` 그대로, 하루 합계와 피드백에 남지 않음, 기능이 꺼졌으면 `BROWSER_DISABLED`. browser-cowork 스펙 REQ2 를 가리킨다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorApprovalBrowserTest.java`(신규, 단위, Mockito): 브라우저 플러그인 + 켜기 성공이면 `ensureRunning` 과 `touch`; 실패 코드 다섯마다 `CONNECTOR_BROWSER_UNAVAILABLE`; `BROWSER_DISABLED` 는 그대로; 브라우저 아닌 플러그인, 남의 줄, `PENDING` 아님, 만료면 `ensureRunning` 을 부르지 않음.
- `backend/src/test/java/com/bifos/assistant/connector/approval/application/ApprovalBrowserUnavailableTest.java`(신규, `testsupport/BackendIntegrationTest` + `@OverrideProperties("assistant.browser.enabled=true")`, 본보기 `browser/application/UserBrowserAccessRevokedTest.java`). `testsupport/FakeBrowserRuntime` 의 실패 동작으로 켜기 실패를 만들고 HTTP `POST /api/v1/connector-actions/{id}/approve` 로 돌린다.
  - 일반 브라우저 플러그인 쓰기 줄: 409 `CONNECTOR_BROWSER_UNAVAILABLE`, 줄 `PENDING`, 피드백 사건 없음, 실행 호출 없음. 켜기를 성공으로 바꿔 다시 승인하면 실행된다
  - 구매 체크아웃 줄(`CheckoutApprovalTest.java` 의 `ownerBrowser` manifest 방식): 같은 409, 줄 `PENDING`, 오늘 구매 합계가 그대로
  - 원고 저장 줄(`connector/ContentDraftSnapshotControllerTest.java` 방식): 같은 409, 줄 `PENDING`
  - 쇼핑의 상한 변경 줄: 켜기 실패 대역에서도 승인된다(브라우저를 켜지 않는다)
- `web/test/unit/connector-action.test.ts`: `connectorActionErrorMessage("CONNECTOR_BROWSER_UNAVAILABLE")` 이 정해 둔 글.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorApprovalBrowserTest' --tests '*ApprovalBrowserUnavailableTest' --tests '*CheckoutApprovalTest' --tests '*ConnectorActionControllerTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
cd web && node --test test/unit/connector-action.test.ts
```

Gradle 은 무거운 검사 대기열로 감싼다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorApprovalBrowser.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/contentdraft/application/ContentDraftSaveApproval.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorApprovalBrowserTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/ApprovalBrowserUnavailableTest.java` | 신규 |
| `web/src/lib/connector-action-error.ts` | 수정 |
| `web/test/unit/connector-action.test.ts` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
