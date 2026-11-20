# Phase 01. 답 모양 지침과 대화 화면, 평가의 형식 칸

**Execution profile**: standard

## 목표

비서 답이 짧은 문단, 제목, 표, Mermaid, 정해진 출처 목록으로 나오고, 대화 화면이 그것을 읽기 쉽게 그리며, 답변 평가가 형식을 참고로 센다.

**범위 외**: 출처 카드 화면, 확대 보기, 말투와 길이, 통과 기준(DECISION10) 변경.

## 컨텍스트

**요구사항**: REQ15, REQ16, REQ17, REQ18, REQ19, REQ20

**근거 문서**: `docs/features/answer-quality/spec.md`, `docs/features/answer-quality/design.md` 의 「PR5 답 모양」

- 지침 글은 `ContextAssembler` 의 `RESPONSE_INSTRUCTIONS`(모든 실행)와 `SEARCH_INSTRUCTIONS`(대화 실행)다
- 채팅 마크다운은 `web/src/components/chat/markdown.tsx`, 개발 문서 마크다운은 `web/src/components/dev-docs/markdown.tsx` 다
- 기준 모양은 스펙 REQ18 의 값이다

## 의도 메모

- `remark-breaks` 를 더하지 않고 같은 일을 하는 플러그인을 직접 쓴다. 의존과 lockfile 변경을 피한다
- 글자 15px 는 임의 값 대신 `globals.css` 의 `--text-answer` 토큰으로 둔다(rule-56). 링크 색은 primary 가 아니라 `info` 토큰이다

## 작업 항목

### 1. `ContextAssembler` 지침 글

`RESPONSE_INSTRUCTIONS` 에 REQ16 규칙을, `SEARCH_INSTRUCTIONS` 의 출처 문장에 REQ15 꼴을 넣는다. `ContextAssemblerTest` 에 두 글이 드는 것과 예약·살펴보기 범위에도 답 모양이 드는 것을 더한다.

### 2. Mermaid 공용화

`web/src/components/dev-docs/mermaid-block.tsx` 를 `web/src/components/ui/mermaid-block.tsx` 로, `web/src/lib/dev-docs/mermaid.ts` 를 `web/src/lib/mermaid.ts` 로 옮기는 커밋을 따로 둔다. import 와 `web/test/unit/dev-docs-mermaid.test.ts` 를 고친다.

### 3. 채팅 마크다운

`web/src/components/chat/markdown-plugins.ts` 에 `remarkSoftBreaks`, `rehypeSourceSection` 을 둔다. `Markdown` 에 `density`, `streaming` prop 과 제목, 인용, 구분선, 출처 절, mermaid 그리기를 더한다. `answer-body.tsx` 가 `streaming` 을, `activity-timeline.tsx` 가 `density="compact"` 를 넘긴다. 플러그인과 「닫히지 않은 mermaid 블록」 판정은 `.ts` 순수 함수로 두어 `web/test/node/chat-markdown-plugins.test.ts` 가 ReactMarkdown 정적 렌더로 확인한다(node 테스트는 `.tsx` 를 읽지 못한다). 부품의 모양은 개발 서버 화면으로 본다.

### 4. 평가 형식 칸

`score.ts` 에 `formatCheck(answer)` 와 보고서 칸을 더하고 `answer-eval-score.test.ts` 에 사례를 더한다.

### 5. 문서

`docs/features/answer-quality.md` 의 「지침」 과 「세는 규칙」, `docs/features/chat.md` 의 답 그리기 설명을 고친다. 이 계획 폴더는 구현 PR 에서 지운다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.context.ContextAssemblerTest'
cd web && pnpm lint && pnpm typecheck && pnpm test:node
node --test test/model-eval/answer/answer-eval-score.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `web/src/components/ui/mermaid-block.tsx` | 신규 (dev-docs 에서 이동) |
| `web/src/components/dev-docs/mermaid-block.tsx` | 삭제 (이동) |
| `web/src/lib/mermaid.ts` | 신규 (dev-docs 에서 이동) |
| `web/src/lib/dev-docs/mermaid.ts` | 삭제 (이동) |
| `web/test/unit/dev-docs-mermaid.test.ts` | 수정 (import) |
| `web/src/components/dev-docs/markdown.tsx` | 수정 (import) |
| `web/src/components/chat/markdown.tsx` | 수정 |
| `web/src/components/chat/markdown-plugins.ts` | 신규 |
| `web/src/components/chat/answer-body.tsx` | 수정 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 수정 |
| `web/src/app/globals.css` | 수정 (토큰) |
| `web/test/node/chat-markdown-plugins.test.ts` | 신규 |
| `test/model-eval/answer/score.ts` | 수정 |
| `test/model-eval/answer/answer-eval-score.test.ts` | 수정 |
| `docs/features/answer-quality.md` | 수정 |
| `docs/features/chat.md` | 수정 |

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ15 | 없음 | 대화 범위로 조립한 지침을 읽는다(`ContextAssemblerTest`) | `### 출처`, `- [기관 · 문서 이름](주소)`, 주소를 글자로 쓰지 않기, 목록 안 한 번이 든다 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ16 | 없음 | 대화, 예약, 살펴보기 범위로 지침을 조립한다 | 세 범위 모두 답 모양 규칙이 들고 표 구분 줄 규칙이 남는다 | 로컬 Gradle 테스트 | worker | 없음 | PR 전 |
| REQ17 | 2026-11-20 운영 답의 출처 블록 원문(주소는 공개 사이트) | 채팅 마크다운으로 정적 렌더한다 | 출처 줄 사이에 `<br>` 가 들고 코드 블록 안 줄바꿈은 그대로다 | web node 테스트 | worker | 없음 | PR 전 |
| REQ18 | 제목, 표, 인용, `### 출처` 목록이 든 답 | 정적 렌더하고 개발 서버에서 넓은 화면과 375px 로 본다 | 제목 크기 클래스, 표 감싸개, 출처 `section` 이 있고 미리보기의 「제안」 과 같은 모양이다. compact 는 지금 크기 | web node 테스트, 로컬 개발 서버 화면 | worker | 없음 | PR 전 |
| REQ19 | 닫힌 mermaid 블록과 닫히지 않은 블록 | streaming 참·거짓으로 정적 렌더한다 | 흐르는 중 닫히지 않은 블록은 코드, 닫히거나 끝나면 그림 부품 | web node 테스트 | worker | 없음 | PR 전 |
| REQ20 | 꼴을 지킨 답과 어긴 답 | `formatCheck` 와 `markdown` 보고서를 부른다 | 네 판정과 요약 수가 기대대로이고 `outcome` 은 바뀌지 않는다 | node 테스트 | worker | 없음 | PR 전 |
| REQ15, REQ18, REQ19, REQ20 | 배포된 운영 | 출처 있는 답 하나를 넓은 화면과 휴대폰에서 보고 답변 평가를 다시 돈다 | 출처 목록이 이름 링크 목록으로 나뉘어 보이고 형식 참고 칸이 나온다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
