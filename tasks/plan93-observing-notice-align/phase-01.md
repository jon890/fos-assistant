# Phase 01. 대화 안내 정렬

**Execution profile**: standard

## 목표

관찰 중 안내와 오류 상자의 왼쪽 끝과 너비를 메시지 열에 맞춘다.
**범위 외**: 문구, 상태 전이, API, 저장 모델 변경.

## 컨텍스트

`web/src/components/chat/message-list.tsx`는 `px-1` 안에서 `mx-auto w-full max-w-3xl`로 메시지 열을 만든다.
`web/src/components/chat/conversation-session.tsx`의 오류 Notice와 observing-notice는 이 너비 틀 밖에 있다.
**근거 문서**: `docs/frontend/chat.md`의 「다른 창에서 답하는 중일 때」.

## 의도 메모

공용 컴포넌트나 새 의존성 없이 두 요소를 하나의 조건부 너비 틀로 묶는다.
제품 요구, 흐름, 모듈 책임, 데이터 계약에 영향이 없어 docs와 ADR은 바꾸지 않는다.

## 작업 항목

### 1. 안내의 공통 너비 틀

`web/src/components/chat/conversation-session.tsx`의 두 안내를 `px-1`인 외부 틀과 `mx-auto w-full max-w-3xl`인 내부 틀에 함께 넣는다.
안내가 모두 없으면 틀도 만들지 않는다.
기존 문구와 mb-2는 유지한다.

### 2. 정렬 브라우저 테스트

`test/browser/observe-running.spec.ts`의 다른 창 안내 검사와 연결 끊김 검사를 보강한다.
메시지 열(`#message-scroll`이 아니라 data-testid message-scroll의 첫 자식)의 boundingBox와 observing-notice를 비교한다.
왼쪽 끝과 너비 차이가 1 CSS 픽셀 미만이어야 한다.
실행 중 중지 API를 503으로 응답시키는 실패 검사를 추가한다.
오류 Notice(`[data-slot="notice"][data-variant="error"]`)와 관찰 안내가 함께 보이는 둘째 창에서 메시지 열과 상자의 왼쪽 끝과 너비를 비교한다.
데스크톱과 모바일 프로젝트에서 실행해 원래의 정렬 누락이 실패로 드러나게 한다.

## 검증

```bash
scripts/check-local.sh observe-running
scripts/quality.sh check
```

두 명령 모두 종료 코드 0이어야 한다.
브라우저 검사 전 공통 지시서의 대기 스크립트를 실행한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/components/chat/conversation-session.tsx` | 수정 |
| `test/browser/observe-running.spec.ts` | 수정 |
