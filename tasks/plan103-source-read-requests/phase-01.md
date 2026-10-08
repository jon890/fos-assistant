# Phase 01. 열람 요청의 호출 성공과 확인된 결과 주소를 구분한다

**Execution profile**: deep

## 목표

긴 원문 결과의 preview가 가려져도 명확히 짝인 시작 주소를 호출 성공 수준의 근거로 보인다.

**범위 외**: DB, plugin, Hermes core 변경, 0건 답 숨기기, 시작 주소를 페이지별 성공으로 승격.

## 컨텍스트

**근거 문서**: `docs/backend/conversation.md`의 「답에서 확인할 수 있는 원문 열람」, `docs/frontend/chat.md`의 「이번에 연 원문」.
`SourceReadSummaries`는 저장된 답의 실행과 같은 사용자 자손을 묶어 조회하고 `SourceReadList`는 이력의 요약을 그린다.
Hermes의 web_extract 시작 preview는 첫 URL 하나이며 완료 preview는 500자로 잘린다. 호출 ID는 없고 말줄임표는 잘림 표시다.

## 의도 메모

- 같은 도구의 시작이 겹친 묶음은 순서로 짝을 추측하지 않는다.
- 시작과 성공 완료가 하나씩 명확히 짝이고 해당 실행이 OBSERVED일 때만 요청 주소를 쓴다.
- 완결된 results가 있으면 결과의 성공·실패 판정을 우선한다. 잘린 결과의 시작 주소는 페이지 성공을 뜻하지 않는다.

## 작업 항목

### 1. backend 근거 수준 분리

`SourceReadSummary`에 requestedUrls 목록을 추가한다. null/missing 실행은 빈 목록이다.
`SourceReadSummaries`는 실행별 사건 순서로 정확한 web_extract의 시작·완료를 짝짓는다.
시작이 겹치면 열린 호출 수가 0이 될 때까지 그 묶음에서 요청 주소를 쓰지 않는다. 실패나 성공 미상 완료도 시작을 소비하되 집계하지 않는다.
결과가 missing·malformed·가림인 성공 완료이며 해당 실행이 OBSERVED이고 시작 하나가 명확하면 시작 detail의 첫 URL을 기존 정리 규칙으로 검사한다.
완결된 results의 빈 배열·항목 오류·정책 차단, 루트 success=false·blocked_by_policy=true는 요청 주소로 대체하지 않는다.
배열 안의 비객체·필수 URL 누락·가림·절단 때문에 판정 가능한 결과가 하나도 없으면 요청 주소 fallback을 허용한다.
성공 URL이나 오류·차단·빈 본문 등 판정 가능한 항목이 하나라도 있으면 결과를 우선하고 미확인 항목은 unresolvedCount로 남긴다.
가림·절단·내부 URL은 요청 주소에도 제외한다. 요청 주소는 정리한 뒤 중복 제거하고 결과 urls와 겹치면 결과를 우선한다.
completedCount와 unresolvedCount는 기존 완료 사건 집계 의미를 유지한다.

### 2. web 근거 수준 표시

Turn의 requestedUrls는 구 서버와 호환되도록 optional 목록이다.
결과 주소와 요청 주소를 문서의 두 제목으로 나눠 표시하고 후자는 페이지별 성공 미확인 안내를 붙인다.
두 목록 모두 없을 때만 기존 빈 상태를 쓴다. 저장된 답 표시, 0건 답 표시와 새 탭 보안 속성을 유지한다.

### 3. 회귀 테스트

`SourceReadRequestsTest`는 명확한 짝의 긴 결과, 시작 누락·겹침·가림·잘림·실패·미상·불완전 관측, 명시적 페이지 실패, 결과 우선과 중복 제거를 확인한다.
시작만 남은 경우와 시작 없는 완료는 요청 주소가 없다. 실패·미상 완료도 시작을 소비하므로 뒤 완료에 잘못 붙이지 않는다.
겹친 묶음의 열린 호출 수가 0이 된 뒤 새 단독 짝은 다시 허용한다.
짝이 있는 실행 자체가 INCOMPLETE면 요청 주소를 숨긴다. 그 실행이 OBSERVED이고 형제만 불완전하면 요청 주소는 유지하고 전체 observationComplete=false를 표시한다.
완결된 결과의 빈 배열·항목 오류·정책 차단과 루트 success=false·blocked_by_policy=true를 시작 URL로 대체하지 않는 테스트도 둔다.
미확인 배열 항목만 있는 경우의 요청 주소 fallback과, 판정 가능한 결과가 섞였을 때 fallback을 하지 않는 경계도 각각 매개변수 테스트로 고정한다.
기존 summary·controller 테스트는 새 목록을 반영한다.
`SourceReadPreviewIntegrationTest`는 1000자 ASCII 본문을 가진 결과를 upstream의 500자 절단과 끝 ASCII ... 규칙으로 preview로 만든 뒤 plain 시작 URL과 함께 실제 HermesRunEventStream 가리기와 ExecutionEventRecorder 엔티티 변환 경로에 넣는다.
긴 결과는 앞 497자와 ... 3자로 총 500자다. ASCII fixture를 써 Python 문자 수와 Java UTF-16 길이 차이를 피한다.
긴 결과는 요청 주소만 남고 짧은 온전한 결과는 결과 주소가 남는다.
`source-reads.spec.ts`는 두 근거 제목·페이지 미확인 안내·옛 응답 호환·빈 상태·새로고침을 확인한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*SourceReadRequestsTest' --tests '*SourceReadPreviewIntegrationTest' --tests '*SourceReadSummariesTest' --tests '*SourceReadMessagesTest'
pnpm --dir web typecheck
pnpm --dir web test:browser source-reads.spec.ts --repeat-each=3 --retries=0
scripts/quality.sh check
```
무거운 검사는 heavy-lock으로 감싼다. backend 전체와 전체 브라우저는 PR CI에서 확인한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/SourceReadSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/SourceReadSummaries.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/SourceReadRequestsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/application/SourceReadSummariesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SourceReadMessagesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/SourceReadPreviewIntegrationTest.java` | 신규 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/components/chat/source-read-list.tsx` | 수정 |
| `test/browser/source-reads.spec.ts` | 수정 |
| `docs/backend/conversation.md` | 수정 |
| `docs/frontend/chat.md` | 수정 |
