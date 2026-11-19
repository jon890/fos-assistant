# Phase 01. Control Plane 의 목록 다시 읽기 경로와 단계 목록 응답

**Execution profile**: standard

## 목표

관리자가 부르는 모델 목록 다시 읽기 경로를 만들고, `MEMBER` 역할의 단계 목록 응답에 모델과 강도를 넣는다.
화면이 이 두 가지에 기대므로 먼저 만든다.

**범위 외**: 화면 변경(phase 02). 새 모델 알림(뒤 PR).

## 컨텍스트

- 다시 읽기는 이미 있다. `ModelOptionsService.refreshedOptionsForAgent` 가 profile 하나를 `refresh=true` 로 다시 읽고, 30초 제한(`MIN_REFRESH_INTERVAL`)과 캐시 갱신을 갖는다. 다만 실패를 삼키고 목록을 돌려줘서 결과를 알 수 없다.
- `GroupModelCatalogService` 는 켜진 에이전트의 profile 을 한 번씩 돌며 목록을 모은다(`catalogFor`). 같은 순회로 다시 읽기를 부른다.
- 역할 판정은 `InternalValuePolicy.visibleTo` 한 자리다. `ModelTierService.optionsFor` 가 `ADMIN` 이 아니면 `nameOnly` 로 세 값을 모두 비운다.
- 관리자 경로는 `admin.model.presentation.ModelAdminController`, 응답 모양은 같은 패키지의 `ModelAdminDtos` 에 둔다.

**요구사항**: REQ1, REQ2, REQ4

**근거 문서**: `docs/features/model-usage/spec.md`, `docs/features/model-usage/design.md` 의 「다시 읽기 경로」 와 「단계 목록 응답」, `docs/adr/ADR-20261119-tier-model-names.md`

## 의도 메모

- 다시 읽기 응답에 목록을 담지 않는다. 화면이 기존 조회를 다시 읽는다(설계 DECISION1).
- 새 메서드는 예외를 내지 않는다. 한 번도 읽지 못한 profile 의 실패도 결과로 답해야 그룹 순회가 끝까지 돈다.
- 시도의 성공과 실패는 Hermes 호출이 끝났을 때 적는다. 기다리는 중에 온 요청은 「새로 묻지 않음」 이다.
- 기존 호출자(`refreshedOptionsForAgent` 를 부르는 실행 때의 단계 해석과 단계 정의 저장)의 동작을 바꾸지 않는다. 기존 테스트 `ModelOptionsServiceTest` 의 다시 읽기 사례가 그대로 통과해야 한다.

## 작업 항목

### 1. `ModelOptionsService` 에 결과를 돌려주는 다시 읽기를 더한다

- `chat.modelchoice.application.dto` 에 결과 타입 `CatalogRefresh` 를 둔다. 값은 `status`(`REFRESHED`, `REUSED`, `FAILED`)와 마지막 성공 시각 `refreshedAt`(`Instant`, 없으면 null)이다. profile 하나의 결과와 그룹으로 모은 결과가 같은 모양이라 타입 하나를 함께 쓴다.
- `ModelOptionsService` 에 `Agent` 를 받아 그 결과를 돌려주는 메서드를 더한다. 판정은 설계 「다시 읽기 경로」 의 profile 결과 표(다섯 줄)를 따른다.
- profile 마다 마지막 시도 시각, 마지막 성공 시각, 마지막으로 끝난 시도의 실패 여부, 진행 중 여부를 든다. 지금의 `refreshedAt` 지도를 이 상태로 바꾼다.
- 읽기에서 난 `ApiException` 은 모두 실패다. 지금의 WARN 로그를 유지한다.
- `refreshedOptionsForAgent` 는 새 메서드를 부른 뒤 지금처럼 `optionsForAgent` 를 돌려준다.

### 2. `GroupModelCatalogService` 에 그룹 전체 다시 읽기를 더한다

- 그룹 결과도 `CatalogRefresh` 다. 모으는 규칙은 그 타입의 정적 메서드에 둔다.
- 켜진 에이전트의 profile 을 한 번씩, 실패해도 끝까지 돈다.
- 모으는 규칙: 하나라도 실패면 `FAILED`, 아니고 하나라도 새로 묻지 않음이면 `REUSED`, 모두 새로 읽음이면 `REFRESHED`. `refreshedAt` 은 profile 들의 마지막 성공 시각 가운데 가장 이른 것이고 하나라도 없으면 null 이다. 켜진 에이전트가 없으면 `REFRESHED` 와 null 이다.

### 3. `ModelAdminController` 에 `POST /api/v1/admin/model-catalog/refresh` 를 더한다

- `currentUser.requireAdmin()` 을 먼저 부른다.
- 응답 모양(`status`, `refreshedAt`)을 `ModelAdminDtos` 에 더한다.

### 4. `ModelTierService.optionsFor` 가 `MEMBER` 역할에게 모델과 강도를 준다

- `ADMIN` 이 아닌 요청자에게 채운 단계마다 `model` 과 `reasoningEffort` 를 넣고 `provider` 는 null 로 둔다.
- `ADMIN` 응답과, provider 를 비운 옛 정의를 에이전트 기본 provider 로 채우는 동작은 그대로다. `MEMBER` 응답을 만들 때는 모델 목록을 읽지 않는다.
- 메서드의 Javadoc 을 새 동작으로 고친다.

### 5. 테스트

- `ModelOptionsServiceTest`: 새로 읽음, 30초 안의 새로 묻지 않음(Hermes 호출이 늘지 않는다), 실패, 한 번도 읽지 못한 profile 의 실패가 예외가 아닌 것, 실패 직후 30초 안의 재요청이 실패인 것, 앞 시도가 Hermes 를 기다리는 동안의 요청이 새로 묻지 않음인 것. 그 파일의 `MutableClock` 을 쓴다. 「기다리는 동안」 은 `readCatalog(.., true)` 의 대역이 답하기 전에 같은 메서드를 한 번 더 부르게 해 만든다.
- `GroupModelCatalogServiceTest`: 가짜 Hermes 가 `refresh=true` 에만 새 모델을 줄 때 다시 읽기 전후의 그룹 목록, 한 profile 이 실패해도 나머지가 새 목록을 드는 것과 `FAILED`, 바로 다시 부르면 Hermes 를 부르지 않고 `REUSED` 이며 `refreshedAt` 이 앞 값과 같은 것, 켜진 에이전트가 없을 때. 3인자 `readCatalog(url, profile, true)` 를 따로 stub 한다. 이 테스트는 시계를 움직이지 않아도 된다. 바로 다시 부르면 30초 안이다.
- 다시 읽은 뒤 그 모델로 단계를 저장할 수 있는지는 새 사례를 만들지 않는다. 단계 저장은 그룹 목록(`catalogFor`)과 비교하고 그 비교는 `ModelTierServiceTest.comparesOnlyChangedStopsWithGroupCatalog` 가 이미 본다. 위 사례가 다시 읽은 뒤 `catalogFor` 에 새 모델이 있는 것을 보면 둘이 이어진다.
- `ModelTierServiceTest`: 기존 `memberGetsTiersWithoutProviderModelOrEffort` 를 새 기대(모델과 강도가 있고 provider 가 null)로 고치고 이름과 `@DisplayName` 도 맞춘다.
- `ModelCatalogRefreshRouteTest`(신규): `MEMBER` 요청이 403 `FORBIDDEN` 이고 `GroupModelCatalogService` 를 부르지 않는 것(Hermes 에 묻는 길은 그 서비스뿐이다), `ADMIN` 요청이 `status` 와 `refreshedAt` 을 받는 것, 실패가 200 의 `FAILED` 인 것. `MockMvcBuilders.standaloneSetup` 에 실제 `CurrentUserProvider` 와 mock 서비스를 주고, 역할은 `workspace/presentation/WorkspaceAdminControllerTest` 처럼 `SecurityContextHolder` 에 `CurrentUser` 를 넣어 만든다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ModelOptionsServiceTest' --tests '*GroupModelCatalogServiceTest' --tests '*ModelTierServiceTest' --tests '*ModelCatalogRefreshRouteTest' --tests '*ModelTierRequestValidationTest'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/modelchoice/application/ModelOptionsService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/modelchoice/application/GroupModelCatalogService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/modelchoice/application/ModelTierService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/modelchoice/application/dto/CatalogRefresh.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/model/presentation/ModelAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/model/presentation/ModelAdminDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelOptionsServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/modelchoice/GroupModelCatalogServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ModelTierServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/admin/model/ModelCatalogRefreshRouteTest.java` | 신규 |
