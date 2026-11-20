# 관리자 영역 재편 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1-REQ6)을 상세히 다루고, PR2(REQ7, REQ8)는 화면 틀 스펙의 같은 머리 틀 부품을 쓴다는 방향만 정한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `web/src/components/shell/admin-shell.tsx` 의 평평한 `LINKS` 를 묶음 셋의 목록으로 바꾼다. 묶음 제목은 링크가 아닌 글자로 그린다. 「요약」(`/admin`)은 주소가 정확히 같을 때만 골라진 것으로 친다. 하위 주소까지 고르면 모든 관리자 화면에서 「요약」 이 함께 골라진다. 실행 하나의 화면을 「사용량과 비용」 으로 치는 규칙은 그대로 둔다 |
| REQ2 | 같은 파일에서 `md` 미만은 가로 줄 대신 `components/ui/sheet.tsx` 의 서랍을 쓴다. 일반 화면 틀(`app-shell.tsx`)의 서랍과 같은 부품, 같은 방향(왼쪽)이다. 링크를 누르면 서랍을 닫는다. 바깥 누르기와 `Esc` 는 Radix 가 처리한다. 넓은 화면의 세로 메뉴는 지금 그대로다 |
| REQ3 | `web/src/app/admin/page.tsx` 의 넘기기를 없애고 요약 화면을 그린다. 머리(제목 「요약」, 설명 한 줄)와 카드 둘은 클라이언트 부품이 그린다. 이름 메뉴의 「관리자 화면」 은 이미 `/admin` 으로 간다(바꾸지 않는다) |
| REQ4 | Control Plane 에 `GET /api/v1/admin/overview/failures` 를 더한다. 그룹 사용자들의 루트 실행 가운데 7일 안의 실패를 세고 최근 세 건을 낸다. 웹은 `/api/admin/overview/failures` 경유로 읽는다 |
| REQ5 | Control Plane 에 `GET /api/v1/admin/overview/monthly` 를 더한다. 그룹 사용자마다 기존 월 합계(`UsageSummaryService.monthly`)를 구해 더한다 |
| REQ6 | 카드마다 따로 읽는다. 한 카드의 실패는 그 카드의 상태만 바꾼다. 읽는 동안은 `components/ui/skeleton.tsx` 로 카드 모양 뼈대를 그린다. `MEMBER` 역할과 역할을 읽지 못한 경우는 지금의 `app/admin/layout.tsx` 가 그대로 처리한다 |
| REQ7 | PR2. 화면 틀 스펙 PR5 가 만든 머리 틀 부품을 관리자 화면들에 씌운다. 이 설계는 부품을 따로 만들지 않는다 |
| REQ8 | PR2. 같은 PR5 의 빈 목록, 실패, 읽는 중 부품을 쓴다 |

## 책임과 경계

- **web `admin-shell.tsx`** 가 관리자 메뉴의 목록, 골라진 줄 판단, 서랍을 갖는다. 메뉴 목록을 다른 파일에 나누지 않는다. 지금도 이 파일이 목록의 원본이다([`web/docs/code-architecture.md`](../../../web/docs/code-architecture.md) 의 「관리자 영역의 틀」).
- **web 요약 부품**(`components/admin/` 아래)이 카드 둘의 읽기와 다시 읽기를 갖는다. `AdminWorkspaceList` 의 「읽기, 실패면 안내와 다시 읽기」 모양을 따른다.
- **backend `admin.usage`** 가 두 엔드포인트와 그룹 범위를 정한다. `admin` 은 기능의 `application` 과 `application.dto` 만 부른다([`backend/docs/code-architecture.md`](../../../backend/docs/code-architecture.md) 의 `ADMIN_USES_ONLY_FEATURE_APPLICATION`). 새 하위 기능을 만들지 않고 기존 `admin.usage` 에 `application` 을 더한다. 두 카드 모두 사용량 기록을 읽는 화면이기 때문이다.
- **`usage`** 가 실패의 정의와 질의, 월 합계 계산을 갖는다. 그룹이라는 개념은 모르고 사용자 번호 목록만 받는다. 그룹의 사용자를 고르는 일은 `user` 가 한다.
- **`user`** 가 그룹의 사용자 번호와 이름을 낸다. `UserDisplayNameService` 에 그룹 사용자의 번호와 이름을 한 번에 내는 메서드를 더한다.

의존 방향은 `admin.usage → usage.*.application`, `admin.usage → user.application` 이다. 기존 방향 그대로이고 새 순환이 없다.

## 인터페이스와 데이터

### Control Plane

| 엔드포인트 | 권한 | 응답 |
| --- | --- | --- |
| `GET /api/v1/admin/overview/failures` | `requireAdmin` | `{since, total, recent: [{id, userName, agentName, startedAt, errorCode, mine}]}` |
| `GET /api/v1/admin/overview/monthly` | `requireAdmin` | `{month, currency, totalExecutions, subscriptionExecutions, actualCostMicros, incomplete}`. `incomplete` 는 `unpricedExecutions`, 금액을 확인하지 못한 도우미(`pendingSubagents + unconfirmedSubagents + unpricedSubagents`), `observationIncompleteExecutions` 가운데 하나라도 0보다 크면 참이다(스펙 DECISION11) |

**실패 질의.** `usage.execution.infra` 에 `FailedRootExecutionRepository` 를 새로 두고 둘을 더한다. `AgentExecutionRepository` 는 파일 길이 상한(500줄)에 닿아 있다. 둘 다 같은 조건이다.

- 조건: `e.userId in :userIds`, `e.rootExecutionId is null`, `e.startedAt >= :since`, `e.status <> RUNNING`, 그리고 `e.status = FAILED` 이거나 `e.errorCode is not null`
- 수: `count(e)`
- 최근 세 건: `left join Agent a on a.id = e.agentId` 로 에이전트 이름을 함께 읽고 `order by e.startedAt desc, e.id desc` 로 세 건. 축별 합계가 이미 같은 조인을 쓴다. 질의 결과 record 는 `usage.execution.domain.FailedExecutionRow` 에 둔다. 저장소가 있는 `execution` 하위 기능이 위 하위 기능 `tree` 의 타입을 쓰면 하위 기능 순서 규칙(`SubFeatureOrder`)에 걸린다. 축별 합계의 `CostByAgent` 와 같은 자리다

조건은 웹의 `executionStatusVariant`(`web/src/lib/execution-status.ts`)가 실패 배지를 고르는 규칙과 같다. 도는 중이면 실패로 치지 않고, 상태가 실패이거나 오류 코드가 있으면 실패다. 세는 단위는 실행 기록 목록과 같은 루트 실행이다(`RootExecutionQuery`).

`usage.tree.application` 에 `RootExecutionQuery.recentFailures(Collection<Long> userIds, Instant since, int limit)` 를 더하고 `usage.tree.application.dto.RecentFailures(long total, List<FailedRoot> recent)` 를 낸다. `FailedRoot` 는 `id, userId, agentName, startedAt, errorCode` 이고 `RootExecutionQuery` 가 `FailedExecutionRow` 를 옮겨 만든다. `RootExecutionQuery` 는 클래스에 트랜잭션을 열지 않지만, 이 메서드는 `@Transactional(readOnly = true)` 로 수와 최근 세 건을 한 트랜잭션에서 읽는다. 따로 읽으면 사이에 생긴 실패로 수보다 많은 줄이 보일 수 있다. `userIds` 가 비면 질의하지 않고 0건을 낸다(빈 `in` 절은 데이터베이스마다 다르게 돈다. `idsHavingChildren` 과 같은 까닭).

**기간.** `since` 는 서울 날짜로 6일 전의 0시다(오늘 포함 7일). 서울 시간대는 `UsageController.HOUSEHOLD_ZONE` 과 같은 값을 쓴다. 지금 그 상수는 세 곳에 따로 있고 주석이 함께 고치라고 적어 두었다. 네 번째 자리가 생기므로 그 주석에 이 자리를 더한다.

**월 합계.** `UsageSummaryService.monthly(Collection<Long> userIds, Instant from, Instant to)` 를 더한다. 사용자마다 기존 `monthly(Long, ...)` 를 불러 `MonthlyUsageSummary` 를 칸마다 더한다. 질의를 새로 쓰지 않으므로 자식 원장 줄과 사건 관측의 규칙이 한 곳에 남는다. 가족 그룹은 사용자가 몇 명이라 질의 수가 사람 수에 비례해도 부담이 없다. 응답의 `totalExecutions` 는 지금 `MonthlyCostView` 처럼 `pricedExecutions + unpricedExecutions` 다.

**그룹 사용자.** `AppUserRepository.findByGroupIdOrderByIdAsc(Long)` 를 더하고 `UserDisplayNameService.namesInGroup(Long groupId)` 가 `Map<Long, String>`(번호 순)을 낸다. 꺼진 사용자도 들어간다. 그 사람이 이번 주에 겪은 실패와 이번 달 비용도 그룹의 기록이기 때문이다.

**남의 실행.** 응답의 `mine` 은 실행의 사용자가 요청한 관리자일 때만 참이다. 웹은 `mine` 인 줄만 `/admin/executions/{id}` 링크로 그린다. 실행 상세 API(`/api/v1/usage/executions/{id}/tree`)는 지금처럼 남의 실행을 숨기므로 주소를 직접 열어도 열리지 않는다(DECISION10). 응답에 대화 번호, 도구 원본, 문맥 항목은 넣지 않는다.

### 웹

| 경로 | 하는 일 |
| --- | --- |
| `app/admin/page.tsx` | 넘기기 대신 요약 부품을 그린다 |
| `app/api/admin/overview/failures/route.ts`, `.../monthly/route.ts` | Control Plane 으로 그대로 넘긴다(`app/api/admin/workspaces/route.ts` 와 같은 모양) |
| `components/admin/admin-overview.tsx` | 머리와 카드 둘. 카드마다 읽기, 실패, 다시 읽기 상태를 따로 갖는다 |
| `components/shell/admin-shell.tsx` | 묶음 메뉴와 서랍 |

카드 안의 시각은 `formatWhen`, 에이전트 이름이 없을 때는 `agentLabel`(`web/src/lib/format.ts`)을 쓴다. 실행 기록 표와 같은 함수다. 금액은 `formatAmount` 로 쓴다(요약 탭과 같다).

## 실패와 일관성

- 두 엔드포인트는 읽기만 한다. 월 합계는 `UsageSummaryService` 의 읽기 전용 트랜잭션, 실패 요약은 위의 메서드 트랜잭션 하나다.
- 카드는 따로 읽어 한쪽 실패가 다른 쪽을 막지 않는다(REQ6). 실패 응답의 본문(오류 코드)은 그리지 않고 「불러오지 못했어요」 만 그린다. 관리자 파일 공간 목록과 같다.
- `MEMBER` 역할이 API 를 직접 부르면 `requireAdmin` 이 403 을 낸다.
- 배포 순서: backend 와 web 이 한 PR 이고 새 엔드포인트만 더하므로 순서 문제가 없다. 옛 웹이 새 backend 를 만나도 새 엔드포인트를 부르지 않는다.
- 스키마 변경이 없다. 마이그레이션이 없다.

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1, REQ2 | 브라우저 테스트(`web/test/browser/admin-area.spec.ts`)를 넓은 화면과 휴대폰 두 프로젝트로 돌린다. 묶음 제목과 줄 순서, 골라진 줄, 서랍 열고 닫기, 가로 넘침 없음 |
| REQ3, REQ6 | 같은 브라우저 테스트. `/admin` 이 넘어가지 않고 카드 둘이 보이는지, 이름 메뉴 입구가 요약으로 오는지, `MEMBER` 역할이 홈으로 가는지. 한 카드 실패는 테스트에서 그 API 경로만 가로채 실패시켜 본다 |
| REQ4 | backend 통합 테스트(H2)가 실패 조건(상태 실패, 오류 코드만 있음, 도는 중, 자식 실행), 기간 경계(서울 날짜 6일 전 0시 앞뒤), 다른 그룹 제외, `mine` 을 본다. 컨트롤러 테스트가 `MEMBER` 역할의 403 을 본다. 브라우저 테스트가 다른 사용자의 줄이 링크가 아닌지 본다 |
| REQ5 | backend 테스트가 두 사용자의 월 합계가 사용자별 합계의 합인지, 사용자 한 명이면 기존 `monthly-cost` 와 같은지 본다. backend 테스트가 `incomplete` 가 세 경우마다 참이 되는지 본다. 웹 단위 테스트나 브라우저 테스트가 `incomplete` 일 때만 안내 줄이 보이는지 본다 |
| MySQL | 새 JPQL 은 Draft CI 의 `backend-mysql` 이 본다 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 새 API 를 둘 자리 | 기존 `admin.usage` 에 `application` 과 두 엔드포인트를 더한다 | 작성자 | 두 카드 모두 사용량 기록을 읽는다. 새 하위 기능을 만들면 패키지 배치 규칙의 목록도 늘어난다 |
| DECISION2 | 그룹 월 합계 계산 | 사용자마다 기존 월 합계를 더한다. 그룹 질의를 새로 쓰지 않는다 | 작성자 | 자식 원장 줄과 사건 관측 규칙(ADR-062)이 한 곳에 남는다. 가족 그룹의 사람 수는 적다 |
| DECISION3 | 엔드포인트를 카드마다 나눌지 | 나눈다 | 작성자 | 스펙 REQ6 이 카드마다 실패를 따로 보이게 한다. 하나로 묶으면 한쪽 질의 실패가 둘 다 지운다 |
| DECISION4 | 요약 줄의 골라짐 | `/admin` 과 정확히 같을 때만 | 작성자 | 다른 줄처럼 하위 주소를 포함하면 모든 관리자 화면에서 「요약」 이 골라진다 |

## 결정 기록 연결

없음: 되돌리기 어려운 장기 결정이 없다. 남의 실행을 열지 않는 규칙은 기존 실행 상세 API 의 권한과 [ADR-063](../../adr/ADR-063-관리자-전용-표시와-동작은-관리자-영역에만-두고-일반-경로의-응답은-서버가-역할에-따라-줄인다.md) 을 그대로 따른다.
