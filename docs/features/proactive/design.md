# 먼저 살펴보기의 발견·문제 후보 테이블 정리 기술 설계

스펙은 [spec.md](spec.md), 지금 동작은 [기능 파일](../proactive.md)이 갖는다.
PR1 은 읽기와 쓰기를 끊고, PR2 는 PR1 배포를 확인한 뒤 테이블과 칸을 지운다(스펙의 「설계」).

## 지금 구조

살펴보기 한 번(`ProactiveCheckRun`)은 입력 조립(`ProactiveCheckInput`)과 결과 처리(`ProactiveCheckResults`)로 나뉜다.

| 무엇 | 지금 쓰는 곳 | 지금 읽는 곳 |
| --- | --- | --- |
| 발견 `proactive_check_finding` | `ProactiveCheckResults.saveFindings` | 다음 입력의 「최근에 알린 발견」, 되풀이 판정(`announcedSince`), 반응 단추 목록과 반응(`CheckFindingReactions`), 보고를 보일 때 발견마다 남기는 `SURFACED`, 기동 이관 |
| 문제 후보 `proactive_check_problem` | 같은 자리 | 다음 입력의 「최근에 받아들인 문제 후보」, 문제 키 중복 판정(`acceptedProblemKeysSince`), 호출 없는 조회 `findEvaluable`, 기동 이관 |
| `proactive_check.report_json` | `ProactiveCheckRun.record` | 지금 화면의 보고 카드, 읽지 않은 보고의 깨우기 건너뛰기 |
| `proactive_check.new_findings`, `reference_findings`, `tree_*_tokens` | `ProactiveCheck.succeed` | 없다 |
| `decision_feedback_event.source_check_id` | `CheckFeedback`, `CheckFindingReactions`, `ConnectorPolicyService` | 없다. 지표(`AttentionMetricsService`)와 정리 작업도 읽지 않는다 |
| 결과 저장 스위치 `assistant.proactive-suggestion.enabled` | 설정 | `ProactiveCheckResults.saveSuggestions` |

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `ProactiveCheckResults` 가 발견과 문제 후보를 판정해 답과 보고를 그리는 데만 쓰고 저장하지 않는다. 제안 저장은 그대로다. 두 엔티티와 저장소는 기동 이관이 읽으려고 PR2 까지 남기고, 쓰는 메서드와 이관 밖의 조회는 지운다(DECISION1) |
| REQ2 | 입력의 두 절을 「최근 제안」 과 「최근 보고에서 알린 것」 으로 바꾼다. 제안은 `FollowUpService` 의 새 조회가, 마지막 반응은 `ReactionLedger` 의 새 조회가 준다. 보고 절에는 알린 발견과 함께 받아들인 문제 키를 싣는다(DECISION7) |
| REQ7 | 보고(`CheckReport`)에 알린 발견의 주제 키·원문 주소·제목과 받아들인 문제 키를 함께 저장하고, 되풀이 판정이 같은 점검 대화의 최근 보고에서 읽는다. 이미 저장된 보고는 Java 마이그레이션이 옛 표에서 채운다(DECISION2) |
| REQ3 | `CheckFindingController`, `CheckFindingReactions`, 화면의 반응 단추와 API 경로를 지운다. 판정의 「관심 없음」 순서를 지운다 |
| REQ4 | `report_json` 이 비었는지와 `report_opened_at` 만 보는 지금 경로를 바꾸지 않는다. 새로 담는 칸은 보고가 있는지에 영향을 주지 않는다 |
| REQ5 | PR2 가 맡는다. PR1 은 지울 칸을 엔티티에서 빼고 쓰기를 멈춘다. 칸은 모두 `NULL` 허용이거나 기본값이 있어 엔티티에서 빼도 넣기가 실패하지 않는다 |
| REQ6 | PR2 가 맡는다. PR1 은 `LegacySuggestionBackfill` 과 그 읽기를 그대로 둔다(DECISION11) |

## 책임과 경계

- `proactive.check` 가 살펴보기 줄, 보고, 되풀이 판정을 계속 소유한다. 새 서비스는 만들지 않는다
- 제안 줄은 `followup` 이 소유한다. 살펴보기는 `FollowUpService` 의 읽기 메서드 하나로 그 대화의 최근 제안을 받는다. `followup` 은 살펴보기를 모른다
- 반응 사건은 `feedback` 이 소유한다. 살펴보기는 `ReactionLedger` 의 읽기 메서드로 제안마다 마지막 사용자 반응을 받는다. 지금처럼 `feedback.infra` 를 직접 읽지 않는다
- 의존 방향은 그대로다. `proactive → followup`, `proactive → feedback` 은 이미 있다

## 인터페이스와 데이터

### 보고에 담는 것(REQ7)

`CheckReport` 에 두 칸을 더한다. 화면에 보내는 `AttentionReport`, `ReportView` 는 다섯 칸을 하나씩 옮겨 담으므로 새 칸은 API 로 나가지 않는다.

| 칸 | 값 | 비면 |
| --- | --- | --- |
| `announced` | 「새로 알릴 것」 으로 판정한 발견 가운데 주제 키가 있는 것의 `topicKey`, `sourceUrl`, `title`. 도메인 record `ReportedFinding` | 빈 목록 |
| `acceptedProblemKeys` | 받아들인 문제 후보의 정규화한 문제 키(`ProblemJudgement.normalizedKey`) | 빈 목록 |

- 보고는 지금처럼 답을 저장하고 돌아온 살펴보기에만 남는다(`ProactiveCheckService.finish` 의 `returned`). 그래서 「사용자가 보지 못한 발견을 이미 알린 것으로 내리지 않는다」 는 지금 규칙이 그대로 지켜진다
- 예약 살펴보기가 새 발견 없이 조용히 끝나면 보고를 남기지 않는다. 지금도 그때 남기는 것은 참고 발견뿐이고 되풀이 판정은 「새로 알릴 것」 만 읽으므로 판정 결과가 같다. 그때 받아들인 문제 후보는 다음 중복 판정에서 빠진다. 문제 후보는 답과 보고에 그리지 않으므로 사용자에게 닿는 차이는 없다. 같은 블록의 제안은 지금처럼 저장돼 지금 화면에 보인다
- 옛 보고는 두 칸이 없다. Jackson 3 의 기본값이 모르는 칸을 무시하고 없는 칸을 `null` 로 읽으므로, 직전 이미지로 되돌려도 새 보고를 읽고 새 이미지는 `null` 을 빈 목록으로 읽는다
- 기존 보고는 `V20261105040320__ProactiveReportAnnouncedBackfill`(Java)이 채운다. 보고가 있는 살펴보기마다 그 살펴보기의 `NEW` 발견(주제 키가 빈 것 제외)과 `ACCEPTED` 문제 후보를 읽어 두 칸을 쓴다. JSON 을 H2 와 MySQL 에서 같은 SQL 로 만들기 어려워 Java 로 쓴다. `report_json` 은 `JSON` 칸이라 H2 에서는 `? FORMAT JSON` 으로 넣어야 글이 JSON 문자열 값이 되지 않는다. 이 마이그레이션이 없으면 배포 직후 첫 살펴보기가 지난 30일에 알린 발견을 다시 알릴 수 있다

### 되풀이 판정(REQ7, DECISION7)

`ProactiveCheckResults.answer` 가 판정 직전에 그 점검 대화에서 `digest-window` 안에 시작했고 보고가 있는 살펴보기를 한 번 읽는다(`ProactiveCheckRepository.findByConversationIdAndReportIsNotNullAndStartedAtAfter`).
그 보고들의 `announced` 로 `Set<AnnouncedKey>` 를, `acceptedProblemKeys` 로 받아들인 문제 키 집합을 만든다.
판정 순서는 ADR-081 의 1부터 7까지 그대로이고 8(「관심 없음」 주제)만 지운다. `FindingJudgement.judge` 의 `dismissedTopics` 인자를 지운다.
문제 후보의 판정(ADR-093)은 그대로이고 받아들인 문제 키의 출처만 바뀐다.
같은 주제만 다시 나온 예약 살펴보기가 답과 보고 없이 끝나는 분기(`ProactiveCheckResults.answer` 의 `SCHEDULED` 분기)는 바꾸지 않는다. 그래서 다음 매일 깨우기가 `UNREAD_REPORT` 로 막히지 않는다.

### 입력(REQ2, DECISION6)

`ProactiveCheckInput.buildInput` 의 마지막 두 절을 바꾼다. 앞의 변화 신호와 기억에서 꺼낸 최근 일은 그대로다.

| 절 | 읽는 것 | 줄 | 상한 |
| --- | --- | --- | --- |
| 최근 제안 | 그 점검 대화에서 에이전트가 낸 할 일 줄(결과 블록의 제안이라 `dedupe_key` 가 있거나, 살펴보기 turn 이 `follow_up_propose` 로 내 `proposed_by_execution_id` 가 있는 줄). `digest-window` 안에 만든 줄. 거절한 줄을 포함하고 만료한 줄(`PROPOSED` 이고 `expires_at` 이 지금 이전)을 뺀다 | `- 제목 · 상태 · 반응` | `digest-max-items`, 최근 것부터 |
| 최근 보고에서 알린 것 | 위 「되풀이 판정」 과 같은 보고들의 `announced` 와 `acceptedProblemKeys` | 발견은 `- 주제 키 · 제목 · 원문 · 알린 날 · 그 뒤 사용자 메시지 수`, 문제 키는 `- 받아들인 문제 키 · 키 · 알린 날` | 발견과 문제 키를 합쳐 `digest-max-items`, 최근 보고부터 |

- 두 절 모두 모델이 쓴 글(제목)을 담으므로 절 전체를 `ExternalData.wrap` 으로 감싼다. 비면 감싸지 않은 「없다」 문장 하나다
- 상태 낱말은 `FollowUpStatus` 마다 Control Plane 이 정한다(제안됨, 챙기는 중, 끝남, 그만둠, 거절함)
- 반응은 그 줄의 사건 열쇠(`follow_up:<공개 식별자>`) 가운데 사용자가 남긴 마지막 반응 사건이다. 받아들임, 거절, 내림, 미룸, 되돌림, 고침, 끝냄만 읽고 노출과 열기는 반응으로 치지 않는다. `DISMISSED` 는 지금 화면에서 숨긴 것과 받아들인 할 일을 그만둔 것을 함께 가리키므로 「내림」 으로 적고, 어느 쪽인지는 같은 줄의 상태가 보인다. 없으면 「없음」 이다
- 만료한 줄을 빼므로 받아들이지 않고 내리기만 한 제안은 7일(제안 수명) 뒤 입력에서 빠진다. 스펙이 「만료한 줄 제외」 로 정했고, 30일 동안 실리는 것은 거절한 줄이다
- 상한은 지금의 `digest-window`(30일)와 `digest-max-items`(20)를 그대로 쓴다. 지금 두 절이 각각 20줄이었으므로 입력 크기가 같다
- `ProactiveCheckInstructions.COMMON_RULES` 의 「관심 없음」 줄은 「최근 제안 가운데 거절했거나 내린 것과 같은 주제는 다시 알리지 않는다」 로 바꾼다. 「최근에 알린 발견」, 「최근에 받아들인 문제 후보」 를 가리키는 줄은 「최근 보고에서 알린 것」 에 맞춘다

새로 더하는 읽기:

| 자리 | 메서드 | 돌려주는 것 |
| --- | --- | --- |
| `FollowUpService` | `recentSuggestions(userId, conversationId, since, now, limit)` | `followup.application.dto.RecentSuggestion(publicId, title, status)` 목록 |
| `FollowUpRepository` | 위 조건의 JPQL 조회 하나 | `FollowUp` 목록. 쪽 크기는 `Pageable` |
| `ReactionLedger` | `lastUserReactions(userId, subjectKeys, types)` | 열쇠마다 마지막 사용자 사건 종류. 기존 `findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc` 를 쓴다 |
| `ProactiveCheckRepository` | `findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc` | 보고가 있는 최근 살펴보기 |

### 지우는 것(PR1)

| 무엇 | 까닭 |
| --- | --- |
| `ProactiveCheckResults` 의 발견·문제 후보 저장, `ProactiveCheckRun.Deps` 의 `findings`, `problems`, `reactions`, `suggestionProperties`, `ProactiveCheckService.finish` 가 저장을 감싸던 트랜잭션 | REQ1, DECISION12. 발견과 문제 후보를 함께 남길 까닭이 사라졌고, 감싼 트랜잭션이 제안 저장의 항목별 트랜잭션을 하나로 묶어 한 항목의 유일 제약 충돌이 전체를 되돌렸다 |
| `CheckFindingController`, `CheckFindingReactions`, `FindingReaction`, `CheckFindingView`, `ProactiveCheckDtos` 의 발견 응답, 웹의 두 API 경로와 `check-finding-api.ts`, `use-check-findings.ts`, `check-finding-reactions.tsx`, `message-list.tsx` 와 `conversation-session-view.tsx` 의 발견 칸 | REQ3, DECISION3 |
| 보고를 보일 때 발견마다 남기던 `SURFACED` | 발견 열쇠가 사라진다. 살펴보기의 `SURFACED` 는 남긴다 |
| `ProactiveSuggestionProperties` 와 `application.yml` 의 그 키, `LivePropertiesConfig` 의 등록 | DECISION12 |
| `ProactiveCheck` 의 `newFindings`, `referenceFindings`, `treeInputTokens`, `treeCachedInputTokens`, `treeOutputTokens` 와 `ProactiveCheckRun.treeTokens` | 읽는 곳이 없다. 비용은 `root_execution_id` 로 실행 줄을 합쳐 얻는다 |
| `FeedbackEntry.sourceCheck`, `FeedbackEvent.sourceCheckId` 와 부르는 세 곳 | DECISION10. 읽는 곳이 없음을 위 표에서 확인했다 |
| `ProactiveCheckRepository` 에서 운영 코드가 부르지 않는 조회(`findEvaluable`, `existsByRootExecutionIdAndTrigger`, `findByIdAndUserId`, `findFirstByUserIdAndAgentIdOrderByIdDesc`, `existsByUserIdAndAgentIdAndReportIsNotNullAndReportOpenedAtIsNull`, `findByUserIdAndStartedAtGreaterThanEqualOrderByIdAsc`, `findByUserIdAndIdIn`, `findByUserIdAndRootExecutionIdIn`), 발견·문제 후보 저장소에서 이관이 쓰지 않는 조회 | 호출이 없거나 이번에 호출이 사라진다 |
| `ProactiveCheckResults.silent`, `ProactiveCheckRun.silent`, `CheckFeedback` 의 자동 실행 분기, `ProactiveCheckAnswerGuard` 의 자동 실행 분기 | 자동 실행 줄을 새로 만드는 경로가 없어 도는 자동 실행도 없다. `chat` 의 `CheckTurn.silent` 기본값과 그것을 읽는 `ChatTurnLifecycle` 의 두 분기는 이 기능의 파일 범위 밖이라 남기고 PR2 에서 함께 지운다 |

남기는 것(PR2 가 지운다): 두 엔티티와 저장소의 이관용 조회, `LegacySuggestionBackfill`, `LegacySuggestionKeys`, 사용자·에이전트 정리의 두 테이블 처리, `CheckTrigger.AUTONOMY` 값과 「마지막 살펴보기」 조회의 자동 실행 제외, 두 테이블의 정리 분류(`purge/tables.tsv`).
`CheckTrigger.AUTONOMY` 를 PR1 에서 지우지 않는 까닭은 아래 DECISION3 이다.

### 문서와 ADR

| 문서 | 바꾸는 것 |
| --- | --- |
| ADR-081 | 판정 조건 표의 8(「관심 없음」)을 지우고, 이미 알린 발견을 보고에서 읽는다고 고친다. 발견을 저장한다는 서술을 고친다. `backend/docs/adr/INDEX.md` 의 요약 줄도 맞춘다 |
| ADR-093 | 문제 후보를 저장하지 않고 받아들인 문제 키만 보고에 남긴다고 고친다. `backend/docs/adr/INDEX.md` 의 요약 줄도 맞춘다 |
| ADR-20261008 / check-finding-reaction | 결정 전체가 대체된다. status 를 `superseded` 로 바꿔 `docs/adr/archive/` 로 옮긴다. 대체한 결정은 ADR-20261101 / proactive-suggestion 의 새 절이다 |
| ADR-20261101 / proactive-suggestion | 옮기는 순서 뒤에 「발견·문제 후보 테이블을 지운다」 절을 더한다. 반응은 제안 줄에서만 받고, 입력과 되풀이 판정이 제안 줄과 보고를 읽는다 |
| ADR-20261007 / decision-feedback | `source_check_id` 를 쓰지 않는다고 고친다 |
| `docs/features/proactive.md` | 「발견 반응」 절을 지우고, 「Control Plane 지시」, 「끝날 때」, 「검사」, 「문제 후보」, 「제안 저장」, 「판단 피드백」 을 고친다 |
| `docs/features/attention.md` | 발견 반응을 가리키는 줄이 있으면 고친다 |
| `backend/docs/data-schema.md` | 두 테이블은 쓰지 않고 이관만 읽는다고, 지운 칸은 엔티티에서 빠졌고 PR2 가 지운다고, `report_json` 의 새 칸을 적는다 |

## 실패와 일관성

- 보고와 판정 재료가 한 줄(`proactive_check`)의 한 저장(`record`)에 들어간다. 지금은 발견 저장과 줄 저장이 따로라 한쪽만 남을 수 있었는데, 이 틈이 사라진다
- 보고 저장이 실패하면 다음 살펴보기는 그 살펴보기를 알린 것으로 보지 않는다. 지금 발견 저장이 실패했을 때와 같다
- 백필 마이그레이션은 보고가 있는 줄만 줄마다 고치고, 한 줄의 갱신 수가 1 이 아니면 멈춘다. 이미 두 칸이 있는 보고는 건너뛰어 다시 돌려도 같다
- PR1 을 직전 이미지로 되돌린 동안 옛 이미지가 남긴 보고에는 두 칸이 없다. 다시 PR1 로 올리면 백필은 이미 돈 버전이라 다시 돌지 않고, 그 기간에 알린 소식이 한 번 더 알려질 수 있다. 되돌리기는 드문 일이고 결과가 「한 번 더 알림」 이라 받아들인다
- 배포 순서: PR1 은 DDL 이 없다. 직전 이미지로 되돌려도 뜬다(지운 칸은 남아 있고 기본값이 있으며, 새 보고 칸은 옛 이미지가 무시한다). 되돌린 동안 옛 이미지가 쓴 발견과 문제 후보는 PR2 직전 기동 이관이 옮긴다
- `CheckTrigger.AUTONOMY` 를 지우려면 그 줄을 먼저 지워야 한다. 그 줄을 지우면 FK 의 `ON DELETE CASCADE` 로 그 살펴보기의 문제 후보가 함께 지워져, PR2 직전 기동 이관이 옮길 대상이 줄어든다. 그래서 줄 삭제와 값 삭제는 이관을 마지막으로 돌린 뒤인 PR2 의 DROP 과 함께 한다

## 검증 전략

| ID | 경계와 환경 | 확인 |
| --- | --- | --- |
| REQ1 | 가짜 Hermes 로 도는 살펴보기 통합 테스트(H2) | 결과 블록의 발견과 문제 후보가 답과 보고에 그려지고 제안 줄이 생기며 두 테이블에 줄이 없다. 운영 코드에서 두 테이블을 쓰는 곳이 없다. 인수 조건의 「엔티티가 없다」 는 이관을 남기는 PR1 이 아니라 PR2 가 확인한다(스펙 DECISION11) |
| REQ2 | 같은 통합 테스트 | 입력에 그 대화의 제안 줄(거절 포함, 만료 제외, 다른 대화 제외)과 반응, 보고의 발견 주제와 받아들인 문제 키가 `<external-data>` 안에 실리고 상한을 넘지 않는다 |
| REQ7 | 판정 단위 테스트와 통합 테스트 | 기존 되풀이 테스트를 보고 기반으로 옮겨, 같은 주제 키와 원문만 다시 나온 예약 살펴보기가 보고 없이 끝나고 다음 예약이 `UNREAD_REPORT` 로 건너뛰지 않는다. 받아들인 문제 키가 같은 기간 안에서 `DUPLICATE` 로 걸린다. 백필 마이그레이션이 옛 표에서 두 칸을 채운다 |
| REQ3 | MockMvc 와 브라우저 테스트 | 옛 반응 경로와 목록 경로가 404 다. 점검 대화 답 아래에 반응 단추가 없다. 지금 화면의 제안 카드 반응 테스트가 그대로 통과한다 |
| REQ4 | 기존 테스트 | 읽지 않은 보고의 건너뛰기와 보고 열기 테스트가 그대로 통과한다 |
| REQ5, REQ6 | PR2 | PR2 의 마이그레이션 테스트와 운영 확인(`fos-home-infra`) |

저장소의 새 JPQL 조회는 `RepositoryQueryMysqlTest` 에도 넣는다(rule-51). 백필 마이그레이션의 데이터 테스트는 H2 와 MySQL 에서 함께 돌린다(`ToolsetHiddenMigrationTest` 와 그 MySQL 하위 클래스의 꼴).

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 되풀이 판정의 재료를 둘 곳 | `proactive_check.report_json` 의 새 칸 두 개. 제안 줄의 `detail_json` 은 쓰지 않는다 | 작성자 | 제안 줄에는 주제 키가 없고 본문은 암호화돼 판정마다 풀어야 한다. 보고는 「사용자에게 보였다」 는 경계와 정확히 같은 때 저장된다 |
| DECISION2 | 이미 있는 보고의 판정 재료 | Java 마이그레이션으로 옛 표에서 채운다 | 작성자 | 채우지 않으면 배포 직후 지난 30일의 소식을 다시 알린다. SQL 의 JSON 함수는 H2 와 MySQL 에서 다르다 |
| DECISION3 | `AUTONOMY` 줄과 값을 지우는 PR | PR2. PR1 은 그 값으로 동작하는 코드만 지운다 | 작성자 | 줄을 지우면 그 문제 후보가 CASCADE 로 지워져 PR2 직전의 마지막 이관 대상이 준다(스펙 DECISION11 과 같은 까닭) |
| DECISION4 | 지울 `proactive_check` 칸 | `new_findings`, `reference_findings`, `tree_input_tokens`, `tree_cached_input_tokens`, `tree_output_tokens`. `tool_calls`, `delegations` 는 남긴다 | 작성자 | 앞의 다섯은 쓰기만 하고 읽지 않는다. 뒤의 둘은 상한으로 멈춘 까닭을 설명하는 값이고 실행 줄에서 다시 얻지 못한다 |
| DECISION5 | 입력의 반응 낱말 | 사용자 반응 사건의 마지막 하나. 노출과 열기는 빼고 상태는 따로 싣는다 | 작성자 | 상태만으로는 숨김과 미룸이 보이지 않는다. 노출은 반응이 아니다(ADR-20261007 / decision-feedback 의 「무응답」) |
| DECISION6 | 대체할 ADR | ADR-20261008 / check-finding-reaction 은 보관으로 옮기고, ADR-081, ADR-093, ADR-20261101 / proactive-suggestion, ADR-20261007 / decision-feedback 은 고친다 | 작성자 | 반응 단추 결정은 전체가 사라지고, 나머지는 일부만 바뀐다(ADR 목록의 「일부만 대체된 ADR 은 옮기지 않는다」) |
| DECISION7 | 받아들인 문제 키를 입력에 싣는가 | 「최근 보고에서 알린 것」 절에 키만 싣는다 | 작성자 | 문제 후보는 답과 보고에 그리지 않아, 입력에 없으면 모델이 무엇을 받아들였는지 모르고 지시의 「같은 문제 키는 달라진 점이 있을 때만」 을 지킬 수 없다. 문제 후보 경로를 통째로 걷어내는 것은 출력 계약을 바꾸는 일이라 이 스펙 범위 밖이다 |
| DECISION8 | 「최근 제안」 에 넣는 줄 | 결과 블록의 제안 줄과 살펴보기 turn 이 `follow_up_propose` 로 낸 줄 | 작성자 | 두 줄 모두 그 점검 대화에서 에이전트가 낸 제안이다. 하나만 실으면 도구로 낸 제안이 되풀이된다 |
