# Phase 05. 그림 참고 사진을 받지 못하면 먼저 알리고, 검토 지적을 반영한다

**Execution profile**: standard

## 목표

그림의 참고 사진을 받지 못하면 비서가 설명만으로 비슷하게 그려 넣지 않고 먼저 그 사실과 대안을 알리게 한다(스펙 REQ7, 2026-11-21 사용자 결정). 독립 verify 의 검토 지적 가운데 스펙과 다른 기능의 결정에 맞춰야 하는 것을 함께 고친다.

**범위 외**: 비서가 찾은 사진을 그림 참고로 받기(별도 스펙). 링크 사진 후보 넓히기(PR2).

## 컨텍스트

phase 01~04 가 끝 줄 꼴, 판정 글, fos-ctx 글, 공통 지침을 넣었다. 지침은 `context/application/ToolFailureInstructions.java`, 그림 바탕 사진 막기 글은 `hermes/plugins/fos-ctx/image_source.py` 다.
답변 품질 평가는 `test/model-eval/answer/` 의 `questions.json` 과 `score.ts` 가 갖고, 문항마다 보는 방식(`check`)이 있다.

**요구사항**: REQ2, REQ3, REQ7

**근거 문서**: `docs/features/tool-fallback/spec.md` 의 REQ7, DECISION12, `docs/features/tool-fallback/design.md` 의 「지침 글」, 「바꾸는 실패 글」, `docs/features/generated-image/spec.md` 의 DECISION25, DECISION32

## 의도 메모

- 참고 사진 실패 글은 「설명만으로 비슷하게 그려 넣지 말고」 와 사진이나 링크를 보내 달라는 대안을 다음 방법으로 준다
- 16장 초과는 어느 사진을 뺄지 사용자가 정한다(generated-image DECISION25). 코드 실행으로 플러그인 부르기는 스펙 REQ2 대로 바로 알림이다
- 승인 이어 가기에서 끝낸 줄의 결과를 붙이는 글은 ADR-092 복구 안내가 끝 줄 역할을 하므로 앞글에 끝 줄을 붙이지 않는다

## 작업 항목

### 1. 공통 지침과 fos-ctx 글

- `ToolFailureInstructions.SECTION` 에 「그림의 참고 사진을 받지 못했으면 설명만으로 비슷하게 그려 넣지 않는다. 먼저 그 사진을 쓰지 못한다는 것과 사용자가 사진을 올리거나 링크를 보내면 된다는 것을 알린다.」 를 더한다
- `image_source.py`: `NOT_REFERENCE`, `UNKNOWN`, `FAILED`, `LINK_REASONS` 의 다음 방법에 같은 뜻을 넣는다. `TOO_MANY` 는 `ask_user`
- `connector_policy.py` 의 `CODE_EXECUTION_MESSAGE` 는 `ask_user`, `approval_guard.py` 의 `APPROVAL_WRITE_MESSAGE` 는 「승인이 필요 없는 다른 경로로 같은 일을 한다」 다음 방법
- `attachment_inspect.py` 에서 인자가 틀린 호출은 `INVALID_ARGS`(다음 방법)와 `code` `invalid_args` 로 답한다

### 2. Control Plane

- `McpFinancialLimitTools.withNext`: 상한을 바꾸지 못한 상태(`unavailable`, `above_maximum`, `no_financial_connection`, `closed`)에 바로 알림 `next` 칸을 더한다. `isError` 는 그대로다
- `ConnectorApprovalResume`: 이어 가지 못한 글(`NONE`, `NOT_YET`, `HELD`, `ENDED`, `RETRY`, `SENT`)이 바로 알림으로 끝난다. 끝낸 줄의 결과를 붙일 때는 `ENDED_LEAD` 를 쓴다

### 3. 답변 품질 평가

- `score.ts` 에 보는 방식 `photo-request` 를 더한다. 답이 사진이나 링크를 보내 달라고 하면 통과이고, 미달이면 세트 사유에 든다
- `questions.json` 에 비서가 찾은 상품 사진을 참고로 그려 달라는 문항 `draw-found-photo` 를 더한다
- `docs/features/answer-quality.md` 의 판정 표에 그 행을 더한다

### 4. 문서

generated-image 의 설계와 기능 파일의 막는 글 표, fos-ctx README, `docs/features/mcp.md`, 기능 파일을 새 글에 맞춘다.

## 검증

```bash
(cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests 'com.bifos.assistant.context.*' --tests '*ToolFailure*' --tests 'com.bifos.assistant.mcp.*' --tests 'com.bifos.assistant.connector.*' --tests '*ArchitectureRules*')
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx*.py'
python3 -m unittest discover -s hermes/tests -p 'test_image_source.py'
python3 -m unittest discover -s hermes/tests -p 'test_attachment_inspect*.py'
node --test test/model-eval/answer/answer-eval-score.test.ts test/model-eval/answer/answer-eval-data.test.ts
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

기대값: 모두 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/application/ToolFailureInstructions.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ToolFailureInstructionsTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpFinancialLimitTools.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpFinancialLimitNextTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorApprovalResume.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/CheckoutApprovalTest.java` | 수정 |
| `hermes/plugins/fos-ctx/image_source.py` | 수정 |
| `hermes/plugins/fos-ctx/connector_policy.py` | 수정 |
| `hermes/plugins/fos-ctx/approval_guard.py` | 수정 |
| `hermes/plugins/fos-ctx/attachment_inspect.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_fos_ctx_next_step.py` | 수정 |
| `hermes/tests/test_image_source.py` | 수정 |
| `hermes/tests/test_attachment_inspect.py` | 수정 |
| `test/model-eval/answer/score.ts` | 수정 |
| `test/model-eval/answer/questions.json` | 수정 |
| `test/model-eval/answer/answer-eval-score.test.ts` | 수정 |
| `test/model-eval/answer/answer-eval-data.test.ts` | 수정 |
| `docs/features/answer-quality.md` | 수정 |
| `docs/features/generated-image/design.md` | 수정 |
| `docs/features/generated-image.md` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `docs/features/tool-fallback.md` | 수정 |
