# Phase 03. 실제 모델 평가 세트와 개념 목록의 주소 검사

**Execution profile**: standard

## 목표

개념마다 문장 여럿을 실제 모델로 돌려 알아듣는지 보는 평가 세트를 만들고, 개념 목록의 화면 주소와 도구 이름이 실제로 있는지 저장소 테스트가 지킨다.

**범위 외**: 운영에서 평가를 돌리는 일. 코디네이터가 한다(스펙 DECISION33).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/service-concepts/design.md` 의 「평가 세트」 와 DECISION4, 스펙 DECISION17, DECISION18, `docs/features/answer-quality.md` 의 「답변 품질 평가」

- 같은 틀: `test/model-eval/answer/run.ts`(명령 `send`, `score`, 환경 변수, 종료 코드), `answer/score.ts`(순수 판정), `answer/answer-eval-score.test.ts`, `answer/answer-eval-data.test.ts`, 공용 클라이언트 `test/model-eval/control-plane.ts`
- 실행 트리의 `events[].toolName` 과 `eventType` 으로 도구를 본다
- CI 는 `node --test 'test/**/*.test.ts'` 로 새 테스트를 돌린다

## 의도 메모

- 운영에 따라 달라지는 값(다른 에이전트의 code, 플러그인 id)은 문항에 심지 않고 환경 변수로 받는다. 값이 없으면 그 문항은 「건너뜀」
- 토큰, 기본 주소, 답 본문은 표준 출력과 결과 파일에 쓰지 않는다. 대화 공개 식별자와 실행 번호만 쓴다(answer 평가와 같다)
- 세트 통과 기준은 돌린 문항의 85% 이상(올림)이다(설계 DECISION4)

## 작업 항목

### 1. `test/model-eval/concepts/questions.json` 신규

`version`, `questions[]`. 항목: `id`, `concept`(`file_space`, `schedule`, `memory`, `follow_up`, `connection`, `model_tier`, `agent_pick`), `messages`, `expect`: `{ "links"?: string[], "linkPrefixes"?: string[], "noLinkPrefixes"?: string[], "tools"?: string[], "includes"?: string[], "absent"?: string[] }`. `links` 는 답의 링크 가운데 경로와 질의 문자열이 똑같은 것이 있어야 하고, `linkPrefixes` 는 그 앞부분으로 시작하는 링크가 있어야 하며, `noLinkPrefixes` 는 그렇게 시작하는 링크가 없어야 한다. `includes` 는 낱말 가운데 하나 이상이 답에 있어야 한다. 개념마다 둘 이상. 자리 `{otherAgentName}`, `{otherAgentCode}`, `{connectorTitle}`, `{connectorId}` 를 쓴다. 예: 「매일 아침 7시에 날씨 알려 줘」 → `linkPrefixes: ["/tasks"]`, `absent: ["만들었", "등록했"]`. 「더 좋은 모델로 답해 줘」 → `includes: ["모델 단계", "입력창"]`, `absent: ["바꿨", "변경했"]`. 「{connectorTitle} 연결해 줘」 → `tools: ["connector_list"]`, `linkPrefixes: ["/connections/{connectorId}"]`, `absent: ["연결했"]`. 「{otherAgentName}로 바꿔 줘」 → `links: ["/new?agent={otherAgentCode}"]`.

아래 셋은 꼭 넣는다. 없는 에이전트 이름(예: 「우주여행 비서로 바꿔 줘」) → `noLinkPrefixes: ["/new?agent="]`. 없는 서비스(예: 「싸이월드 연결해 줘」) → `links: ["/connections"]`, `includes: ["없"]`, `absent: ["연결했"]`. 있는 서비스의 연결 문항에 `absent: ["연결했"]`.

### 2. `test/model-eval/concepts/score.ts` 신규

순수 함수: 자리 채우기(`fill`), 답의 링크 찾기(마크다운 링크와 맨 주소, 상대 주소 포함), 도구 이름이 `mcp__fos_assistant__<도구>` 로 끝나는 `TOOL_COMPLETED` 가 있는지, `absent` 낱말이 없는지, 문항 판정, 세트 판정(85% 올림, 건너뛴 문항은 분모에서 뺀다), 보고서 마크다운.

### 3. `test/model-eval/concepts/run.ts` 신규

`answer/run.ts` 와 같은 명령, 같은 환경 변수(`FOS_EVAL_BASE_URL`, `FOS_EVAL_TOKEN`, `FOS_EVAL_AGENT_CODE`, `FOS_EVAL_TIER`)에 위 네 자리 변수를 더한다. `send` 는 문항마다 새 대화, `score` 는 마지막 turn 의 답과 실행 트리로 판정해 `report.json` 과 `report.md` 를 쓰고 통과한 대화를 지운다.

### 4. 테스트

- `test/model-eval/concepts/concepts-eval-score.test.ts`: 링크 찾기(상대 주소, 마크다운, 질의 문자열), 도구 판정, absent, 자리 채우기와 빈 값의 건너뜀, 세트 기준 경계(85% 올림)
- `test/model-eval/concepts/concepts-eval-data.test.ts`: 문항 형식, 개념마다 둘 이상, id 중복 없음, 자리 이름이 정해진 넷뿐
- `test/repo/service-concepts.test.ts`: MCP 도구 목록(`McpToolCatalog.java` 와 도구 클래스의 이름 상수)에 모델 단계나 에이전트를 바꾸는 도구(`tier`, `model` 이 든 이름)가 없는지 본다(REQ2). `ServiceConceptsInstructions.java` 의 주소마다 `web/src/app` 의 경로가 있고(질의 문자열을 떼고 `{id}`, `<code>` 를 동적 경로 폴더와 맞춘다), 백틱이나 낱말로 적은 도구 이름(`connector_list`, `agent_list`, `follow_up_propose`, `memory_remember`, `file_space_put`)이 `McpToolCatalog` 나 도구 클래스에 있는지 본다. 「사용량」 이 없는지도 본다

### 5. 문서

`docs/features/service-concepts.md` 에 「평가 세트」 절(파일 표, 환경 변수, 전제, 세는 규칙)과 `covers:` 에 `test/model-eval/concepts/` 를 더한다.

## 검증

```bash
node --test test/model-eval/concepts/concepts-eval-score.test.ts test/model-eval/concepts/concepts-eval-data.test.ts test/repo/service-concepts.test.ts
node --experimental-strip-types --check test/model-eval/concepts/run.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts scripts/ci/feature-covers.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/model-eval/concepts/questions.json` | 신규 |
| `test/model-eval/concepts/score.ts` | 신규 |
| `test/model-eval/concepts/run.ts` | 신규 |
| `test/model-eval/concepts/concepts-eval-score.test.ts` | 신규 |
| `test/model-eval/concepts/concepts-eval-data.test.ts` | 신규 |
| `test/repo/service-concepts.test.ts` | 신규 |
| `docs/features/service-concepts.md` | 수정 |
