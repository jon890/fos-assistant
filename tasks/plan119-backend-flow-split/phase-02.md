# Phase 02. 모듈 flow 와 prd 의 절을 docs/features/ 의 기능 파일 15개로 옮긴다

**Execution profile**: standard

## 목표

`backend/docs/flow.md`, `web/docs/flow.md`, `web/docs/prd.md`, `docs/flow.md` 의 절과 `docs/prd.md` 의 기능별 절을 **문장을 바꾸지 않고** `docs/features/` 의 기능 파일 15개로 옮긴다.
옛 파일 넷을 지우고, 저장소 전체의 링크와 코드 주석의 문서 참조(「절」 이름 포함)를 새 자리로 고친다.
문서 이름 규칙 시험이 모듈 `prd.md` 와 `flow.md`, 루트 `flow.md` 를 더는 받지 않게 한다.

**범위 외**: 문장을 고치거나 줄이는 것, 같은 흐름을 한 번만 남기는 것, mermaid 흐름을 더하는 것, `backend/docs/data-schema.md` 를 줄이는 것, 기능 파일 머리의 `covers:` 줄. 모두 이 PR 다음의 PR 이 한다.
옮기며 문장을 다듬으면 이 커밋의 diff 에 섞여 리뷰어가 옮긴 것과 바뀐 것을 구분하지 못한다.

## 컨텍스트

**근거 문서**: phase 01 이 만드는 feature-docs ADR, `docs/adr/ADR-20261009-docs-per-module.md`

- 코드 주석 약 200개 파일이 `{@code backend/docs/flow.md} 의 「절 이름」` 꼴로 문서를 가리키고, `test/unit/doc-references.test.ts` 가 그 파일과 절이 있는지 본다. 절 이름을 지키면 경로만 바꾸면 된다.
- 문서 안의 상대 링크와 링크 뒤 「절」 은 `test/unit/doc-links.test.ts`, 문서가 백틱으로 적은 저장소 경로는 `test/unit/doc-code-references.test.ts`, 한 문서 안의 헤딩 중복은 `doc-references.test.ts` 가 본다.
- 절 이름으로 읽는 이름은 `test/unit/markdown.ts` 의 `sectionNames` 가 정한다. 헤딩 글과 줄 머리의 굵은 항목 이름이고, 백틱과 `{@code}` 를 벗긴 글로 비교한다.
- 문서를 직접 읽는 시험: `test/unit/attention.test.ts` 의 `NOW_DOC` 이 `web/docs/prd.md` 의 「이유 문구」 표와 「동작」 표를 읽는다. 둘 다 `## 지금 화면` 아래에 있어 `docs/features/attention.md` 로 간다.
- `scripts/pr-risk-labels.sh` 가 `backend/docs/flow.md` 를 Hermes 연동 위험 경로로 보고, `test/unit/pr-risk-labels.test.ts` 가 그것을 단언한다.

### 옮기는 표

원재료 약칭: B=`backend/docs/flow.md`, WF=`web/docs/flow.md`, WP=`web/docs/prd.md`, RF=`docs/flow.md`, RP=`docs/prd.md`.
적은 이름은 그 원재료의 `##` 절이다. `###` 이라고 적은 것은 그 `###` 절과 그 아래만 옮긴다.

| 새 파일 | 제목 | 옮길 절 |
| --- | --- | --- |
| `docs/features/chat.md` | 대화 | B 「대화와 실행 사건」 의 머리글과 `### 대화`, B 「대기열과 중지」 / WF 「대화 이력」, 「에이전트가 물을 때」, 「다른 창에서 답하는 중일 때」, 「다시 생성」 / WP 「대화 목록」, 「기다리는 동안 보이는 것」, 「새 대화 화면」, 「메시지 동작」 / RF 「대화 한 번」 |
| `docs/features/execution.md` | 실행 기록과 한도 | B 「대화와 실행 사건」 의 `### 실행 사건`, `### 모델 단계와 자식 기록`, `### 도구 내용 가리기`(새 `## 실행 기록` 아래에 둔다), B 「사용자 실행 한도」 / WF 「실행 하나를 다시 볼 때」 / WP 「작업 과정 블록」 / RF 「실행이 실패할 때」 |
| `docs/features/model-usage.md` | 모델과 사용량 | B 「모델 단계와 실행 기록」 / WF 「모델을 고를 때」 / WP 「사용량 화면의 탭」 |
| `docs/features/attachment.md` | 사진 첨부와 결과물 | B 「사진 첨부」, 「결과물 파일」 / WF 「사진을 보낼 때」 |
| `docs/features/workspace.md` | 파일 공간 | WP 「파일 공간」 / RF 「파일 공간을 열 때」 |
| `docs/features/agent-skill.md` | 에이전트와 스킬 | B 「에이전트」, 「다른 에이전트에게 맡기기」, 「스킬」 / WF 「결과 다시 전달」 / WP 「에이전트 화면」(아래 셋째 단락에서 다른 파일로 가는 `###` 제외), WP 「관리자 영역」 의 `### 도구 사용 요청`, `### 화면에 보일 도구` / RP 「언제 에이전트를 나누는가」 |
| `docs/features/mcp.md` | MCP 와 토큰 | B 「MCP 요청자」 / RF 「두 방향과 두 토큰」 |
| `docs/features/users.md` | 사용자와 로그인 | B 「사용자를 더할 때」 / RF 「로그인 활동 기록」 / WF 「꺼진 사용자의 세션」 / WP 「관리자 영역」 의 머리글(`###` 둘 제외) |
| `docs/features/user-browser.md` | 사용자 브라우저 | B 「사용자 브라우저」 |
| `docs/features/connector.md` | 커넥터 연결 | B 「커넥터 설치」, 「커넥터 연결 API」 / RF 「커넥터를 붙일 때」 / WP 「에이전트 화면」 의 `### 연결 절`, `### 연결 뒤 에이전트 고르기` / RP 「커넥터」 |
| `docs/features/connector-policy.md` | 커넥터 도구 정책과 승인 | B 「커넥터 도구 정책」, 「커넥터 승인」 / RF 「커넥터 READ 데이터의 흐름」 / WP 「이번에 연 원문」, 「동작을 승인할 때」 |
| `docs/features/memory.md` | Memory | B 「Memory」, 「문맥 묶음」, 「Memory 회수 측정」 / WP 「기억 화면」, 「참고한 기억」, 「에이전트 화면」 의 `### 기억 영역 절` / RF 「기억을 남길 때」 / RP 「Memory 에서 아직 만들지 않은 것」 |
| `docs/features/attention.md` | 지금 화면과 알림, 할 일 | B 「먼저 알리기와 지금 화면의 판정」, 「할 일」, 「알림」 / WP 「지금 화면」 / RF 「지금 화면을 열 때」, 「할 일을 제안할 때」 |
| `docs/features/schedule.md` | 예약 작업 | B 「예약 작업」 |
| `docs/features/proactive.md` | 먼저 살펴보기 | B 「먼저 살펴보기」, 「매일 루프」, 「문제 후보의 가치 평가」, 「행동 정책」, 「판단 피드백」, 「먼저 살펴보기 루프 평가」 / WF 「점검 대화」 / WP 「에이전트 화면」 의 `### 먼저 살펴보기 절`, `### 가치 평가 절` / RF 「먼저 살펴보기」 / RP 「답하는 비서에서 먼저 챙기는 비서로」 |

- 원재료의 `##` 절은 기능 파일에서도 `##` 다. 부모 없이 옮기는 `###` 절은 `##` 로 한 단 올린다. 그 아래 `####` 도 한 단씩 올린다. 헤딩 글은 바꾸지 않는다.
- 한 파일 안의 순서: RP 절, WP 절(요구와 화면), RF 절(전체 흐름), WF 절(화면 흐름), B 절(backend 흐름). 같은 원재료 안에서는 원래 순서를 지킨다.
- 파일 머리: `# <제목>` 다음 빈 줄, 그 기능이 무엇인지 한 문장. 원재료 파일 머리의 안내 단락(「화면마다 누구에게…」, 「Control Plane 이 기능마다…」, 절 목차)은 옮기지 않는다.
- 루트 `docs/prd.md` 에는 「목적」, 「범위와 확인 방법」, 「범위 밖」, 「아직 정하지 않은 것」, 「아직 만들지 않은 것」 이 남는다. 이 파일 머리에 기능 파일은 `docs/features/` 에 있다는 한 줄과 폴더 링크를 둔다.

### 헤딩이 겹칠 때

한 기능 파일에 같은 헤딩 글(정규화 뒤)이 둘 이상 생기면, B 에서 온 헤딩은 그대로 두고 다른 원재료에서 온 헤딩 끝에 원재료 표시를 괄호로 붙인다: WP 는 `(화면)`, WF 는 `(화면 흐름)`, RF 는 `(전체 흐름)`, RP 는 `(제품 범위)`. 둘 다 B 가 아니면 뒤에 오는 쪽에 붙인다.
붙인 뒤에도 겹치면 멈추고 `PHASE_BLOCKED: 헤딩 중복 <파일> 「<글>」` 을 낸다.

## 의도 메모

- 스크립트로 옮긴다. 손으로 자르고 붙이면 줄을 잃는다. 스크립트는 scratchpad 에 두고 저장소에 넣지 않는다.
- 스크립트가 만드는 대응표 `(옛 파일, 옛 절 이름) → (새 파일, 새 절 이름)` 를 링크 고치기와 참조 고치기 모두에 쓴다. 대응표에는 `##`~`####` 헤딩과 굵은 항목 이름(`sectionNames` 가 읽는 것)을 모두 넣는다.
- 원재료에 위 표에 없는 `##` 절이 있으면(다른 브랜치가 더했을 수 있다) 멈추고 `PHASE_BLOCKED: 옮기는 표에 없는 절 <파일> 「<이름>」` 을 낸다.
- `AGENTS.md` 계열은 링크만 고친다. 예외는 루트 `AGENTS.md` 의 「모듈마다 `docs/` 에 `prd.md`, `flow.md`, …」 한 문장이다. 이 phase 뒤에는 틀린 규칙이 되므로, 기능 파일은 `docs/features/` 에 두고 모듈 `docs/` 에는 `code-architecture.md` 와 backend 의 `data-schema.md` 만 둔다는 한 문장으로 바꾼다.

## 작업 항목

### 1. 기능 파일 만들기

1. 원재료 다섯 파일을 코드 펜스를 건너뛰며 헤딩 단위로 자르고, 위 표대로 15개 파일에 붙인다. 옮긴 줄 수를 원재료별로 세어, 「원재료 줄 수 − 옮기지 않은 머리와 RP 에 남는 절 = 기능 파일에 들어간 원재료 줄」 이 맞는지 스크립트로 확인한다.
2. `git rm backend/docs/flow.md web/docs/flow.md web/docs/prd.md docs/flow.md` 하고, `docs/prd.md` 에서 옮긴 네 절을 지운다.
3. 옮긴 본문의 상대 링크를 고친다. 링크 대상을 원래 파일 자리 기준으로 풀어 저장소 경로를 얻고, `docs/features/` 기준 상대 경로로 다시 쓴다. 대상이 옮긴 문서(옛 파일 넷, 또는 RP 의 옮긴 절)면 뒤에 붙은 「절」 로 대응표에서 새 파일과 새 절 이름을 찾는다. 같은 기능 파일이면 `](<같은 파일>.md)` 로 쓴다.

### 2. 저장소 전체의 참조 고치기

4. 옛 경로를 가리키는 모든 줄을 찾는다: `git grep -nE "(backend|web)/docs/(flow|prd)\.md|(^|[^/a-zA-Z])docs/flow\.md"`, 그리고 `backend/docs/`, `web/docs/` 안에서 `](flow.md`, `](prd.md` 로 건 링크, `docs/prd.md` 「옮긴 절」 참조.
   - 뒤에 「절」 이 붙은 참조: 대응표의 새 파일과 새 절 이름으로 바꾼다. 「A」, 「B」 처럼 이어 적은 절이 서로 다른 파일로 갔으면 문장을 나눠 각 파일을 적는다.
   - 절 없는 참조: 문맥(그 코드의 패키지, 문서 절의 주제)으로 기능 파일 하나를 고른다. 고를 수 없으면 폴더 `docs/features/` 로 쓴다.
   - Markdown 링크는 그 파일 자리 기준 상대 경로로 고친다. 링크 글자의 옛 경로도 새 경로로 바꾼다.
   - 대상 파일 종류: Java, TypeScript, Python 의 주석, `.github/workflows/*.txt` 프롬프트, `README.md`, `README.ko.md`, 모든 `AGENTS.md`, `hermes/**/README.md`, 남는 모듈 문서(`*/docs/code-architecture.md`, `backend/docs/data-schema.md`, `hermes/docs/hermes-contract.md`, `docs/code-architecture.md`, `docs/privacy.md`, `docs/self-hosting.md`), 모든 `docs/adr/**`.
   - 예외: `test/unit/` 의 문서 검사 시험이 예시 문자열로 쓴 옛 경로(`doc-references.test.ts` 의 `PATH_FIXTURE_FILES` 에 든 파일과 파서 시험의 입력)는 그대로 둔다.
   - Java 주석의 줄이 길어져 Checkstyle 줄 길이를 넘으면 그 주석만 줄바꿈한다.
5. 모듈 `AGENTS.md` 의 문서 링크 표나 목록에서 지운 파일을 가리키던 줄은 해당 기능 파일 또는 폴더 `docs/features/` 링크로 바꾼다. 루트 `AGENTS.md` 의 「읽기 순서」 표의 `docs/flow.md` 줄은 `docs/features/` 폴더 링크로 바꾸고 설명 칸은 「기능마다 요구와 화면, Control Plane, Hermes 를 가로지르는 흐름」 으로 바꾼다.
6. `test/unit/attention.test.ts` 의 `NOW_DOC` 를 `docs/features/attention.md` 로, 시험 이름과 메시지의 `web/docs/prd.md` 를 새 경로로 바꾼다.
7. `scripts/pr-risk-labels.sh` 의 `backend/docs/flow.md` 를 `docs/features/*.md` 로 바꾼다(옛 파일도 모든 기능을 담았으므로 같은 넓이다). `test/unit/pr-risk-labels.test.ts` 의 단언을 `docs/features/mcp.md` 로 바꾸고, `backend/docs/code-architecture.md` 가 라벨이 없는 단언은 그대로 둔다.

### 3. 문서 이름 규칙을 새 배치에 맞춘다

8. `test/unit/doc-files.test.ts`
   - 모듈(`backend/`, `web/`, `hermes/`)의 `docs/` 바로 아래에는 `code-architecture.md` 와 `data-schema.md` 만 통과한다. 루트 `docs/` 바로 아래에는 `prd.md` 와 `code-architecture.md` 만 통과한다. 예외 목록(`EXCEPTIONS`)과 `docs/features/<이름>.md` 는 그대로 통과한다.
   - 기존 시험 입력 중 `web/docs/flow.md` 는 이제 실패 쪽으로 옮기고, `backend/docs/flow.md`, `web/docs/prd.md`, `docs/flow.md` 실패를 더한다. 오류 문구 「새 주제는 prd, flow, code-architecture, data-schema 의 절로 더한다」 는 「기능은 docs/features/ 의 기능 파일이나 그 절로 더한다」 로 바꾼다.

### 4. 이 phase 를 검증하는 시험

9. 위 6~8 의 시험이 새 경로와 새 규칙의 통과 입력과 실패 입력을 단언한다. 문서 검사 시험 넷(`doc-files`, `doc-links`, `doc-references`, `doc-code-references`)이 옮긴 결과 전체를 본다.

## 검증

```bash
node --test test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts test/unit/doc-references.test.ts test/unit/doc-links.test.ts test/unit/attention.test.ts test/unit/pr-risk-labels.test.ts test/unit/file-length.test.ts test/unit/adr-index.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/check-file-length.mjs
test -z "$(git ls-files backend/docs/flow.md web/docs/flow.md web/docs/prd.md docs/flow.md)"
test "$(git grep -nE '(backend|web)/docs/(flow|prd)\.md|(^|[^/a-zA-Z])docs/flow\.md' -- ':!test/unit/doc-*.test.ts' ':!test/unit/file-length.test.ts' ':!tasks/' | wc -l | tr -d ' ')" = "0"
cd backend && ./gradlew checkstyleMain checkstyleTest --quiet
cd backend && ./gradlew test --tests '*.AttentionControlServiceTest' --tests '*.AttentionJudgeTest' --tests '*.AttentionMetricsServiceTest' --tests '*.AttentionServiceTest' --tests '*.FollowUpAttentionSourceTest' --tests '*.SurfacedProblemCandidatesTest' --tests '*.BrowserGatewayTest' --tests '*.GatewayRewriterTest' --tests '*.UserBrowserServiceTest' --tests '*.BrowserPropertiesTest' --tests '*.BrowserGatewayControllerTest' --tests '*.BrowserGatewayHandshakeTest' --tests '*.BrowserGatewaySocketTest' --tests '*.UserBrowserControllerTest' --tests '*.UserBrowserScreenControllerTest' --tests '*.ConnectorActionDeliveryTest' --tests '*.MemoryUseServiceTest' --tests '*.ResultDeliveryRetryTest' --tests '*.ApprovalNotificationTest' --tests '*.ConnectorActionServiceTest' --tests '*.ConnectorPolicyEndpointTest' --tests '*.ConnectorPolicyRequestTest' --tests '*.MemoryRecallEvalTest' --tests '*.FollowUpProposalTest' --tests '*.FollowUpServiceTest' --tests '*.ToolDetailRedactorTest' --tests '*.McpFollowUpToolTest' --tests '*.McpMemoryRememberToolTest' --tests '*.NotificationServiceTest' --tests '*.SurfacedProblemsTest' --tests '*.TaskControllerTest' --tests '*.TaskFiringTest' --tests '*.TaskRunRecoveryTest' --tests '*.TaskRunStarterTest' --tests '*.TaskScheduleTest' --tests '*.TaskServiceTest'
node --test test/unit/artifact-attachment-route.test.ts test/unit/attention.test.ts test/unit/pr-risk-labels.test.ts test/unit/pr-size.test.ts
cd hermes && python3 -m pytest -q tests/test_read_data_flow.py tests/test_fos_ctx.py
```

- 모든 줄이 종료 코드 0 이다. 셋째 줄의 기능 파일 500줄 알림은 있어도 된다(다음 PR 이 줄인다).
- 줄 수: 커밋 메시지 본문에 원재료 다섯 파일의 줄 수와 기능 파일 15개의 줄 수를 적는다. 기능 파일 합계는 원재료 합계에서 옮기지 않은 머리와 RP 에 남은 절을 빼고 새 머리를 더한 값과 맞아야 한다.
- backend 시험과 hermes 시험은 주석 경로만 바뀌었으므로 기존과 같게 통과한다. hermes 시험 환경이 없으면 `PHASE_BLOCKED` 대신 회신에 「돌리지 못함」 과 까닭을 적는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/docs/flow.md` | 삭제 |
| `web/docs/flow.md` | 삭제 |
| `web/docs/prd.md` | 삭제 |
| `docs/flow.md` | 삭제 |
| `docs/prd.md` | 수정 |
| `docs/features/chat.md` | 신규 |
| `docs/features/execution.md` | 신규 |
| `docs/features/model-usage.md` | 신규 |
| `docs/features/attachment.md` | 신규 |
| `docs/features/workspace.md` | 신규 |
| `docs/features/agent-skill.md` | 신규 |
| `docs/features/mcp.md` | 신규 |
| `docs/features/users.md` | 신규 |
| `docs/features/user-browser.md` | 신규 |
| `docs/features/connector.md` | 신규 |
| `docs/features/connector-policy.md` | 신규 |
| `docs/features/memory.md` | 신규 |
| `docs/features/attention.md` | 신규 |
| `docs/features/schedule.md` | 신규 |
| `docs/features/proactive.md` | 신규 |
| `docs/adr/*.md` | 수정 |
| `backend/docs/adr/*.md` | 수정 |
| `web/docs/adr/*.md` | 수정 |
| `hermes/docs/adr/*.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/privacy.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `hermes/docs/code-architecture.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/README.md` | 수정 |
| `hermes/connectors/README.md` | 수정 |
| `hermes/connectors/*/README.md` | 수정 |
| `hermes/plugins/*/README.md` | 수정 |
| `hermes/plugins/**/*.py` | 수정 |
| `README.md` | 수정 |
| `README.ko.md` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
| `hermes/AGENTS.md` | 수정 |
| `.github/workflows/*.txt` | 수정 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `scripts/pr-risk-labels.sh` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionControlServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionMetricsServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/SurfacedProblemCandidatesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/BrowserPropertiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayHandshakeTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserScreenControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MemoryUseServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ApprovalNotificationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalScoreboard.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpProposalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailRedactorTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFollowUpToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryRememberToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/notification/NotificationServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/SurfacedProblemsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/eval/EvalDataset.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/eval/EvalScoreboard.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskFiringTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunRecoveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskScheduleTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` | 수정 |
| `backend/src/test/resources/proactive-eval/scenarios.json` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/tests/test_read_data_flow.py` | 수정 |
| `test/e2e/fake-hermes/connectors.ts` | 수정 |
| `test/e2e/mcp-context.ts` | 수정 |
| `test/e2e/scenarios/attention.ts` | 수정 |
| `test/e2e/scenarios/follow-up-mcp.ts` | 수정 |
| `test/e2e/scenarios/follow-up.ts` | 수정 |
| `test/e2e/scenarios/memory-remember-mcp.ts` | 수정 |
| `test/unit/artifact-attachment-route.test.ts` | 수정 |
| `test/unit/attention.test.ts` | 수정 |
| `test/unit/pr-risk-labels.test.ts` | 수정 |
| `test/unit/pr-size.test.ts` | 수정 |
| `web/src/components/agent/agent-admin-section.tsx` | 수정 |
| `web/src/components/browser/browser-screen.tsx` | 수정 |
| `web/src/components/browser/use-screen-stream.ts` | 수정 |
| `web/src/components/workspace/workspace-explorer.tsx` | 수정 |
| `web/src/lib/agent-memory.ts` | 수정 |
| `web/src/lib/attention.ts` | 수정 |
| `web/src/lib/follow-up-api.ts` | 수정 |
