# Phase 01. 사용자는 칸 슬라이더 하나로 고른다

**Execution profile**: standard

## 목표

입력창의 모델 선택을 ChatGPT 의 Thinking 슬라이더처럼 칸 하나를 고르는 조절로 바꾼다.
`CUSTOM` 모드와 사용자의 provider, 모델, effort 고르기를 지우고, 기존 `CUSTOM` 대화를 선택 없음으로 옮긴다.

## Blocked 조건

- `plan-model-unify-2-stops` 와 `plan-model-unify-3-agent-follow` 가 main 에 머지돼 있어야 한다.

## 컨텍스트

- 결정과 ChatGPT 조사: `docs/adr/ADR-20261101-model-setting.md` 의 「결정」 과 「ChatGPT 모델 선택 조사」. 칸, 기본 칸, 자동 전환을 두지 않는 까닭이 거기 있다.
- 지금 화면: `web/src/components/chat/model-picker.tsx`, `web/src/components/chat/use-composer-model.ts`. 분기는 `docs/features/model-usage.md` 의 「모델 선택이 갈리는 지점」 과 그 아래 목록이 갖는다.
- 브라우저 테스트: `test/browser/model-choice.spec.ts`.

## 작업 항목

### 1. 화면

- 입력창의 모델 단추를 누르면 칸 슬라이더가 열린다. 관리자가 비운 칸은 보이지 않고, 칸이 하나도 없으면 단추를 그리지 않는다.
- 슬라이더는 키보드로 움직일 수 있어야 한다. `input type="range"` 나 radio 묶음 가운데 브라우저 테스트가 값을 고르기 쉬운 쪽을 고르고, 고른 까닭을 PR 「결정」 에 적는다.
- 처음 위치는 대화에 적힌 칸, 없으면 사용자 기본 칸, 그것도 없으면 그룹 기본 칸이다.
- **사용자가 고른 칸은 그 사용자의 기본 칸으로도 저장해 다음 새 대화에 남긴다.** ChatGPT 가 고른 thinking 시간을 다음 대화에 남기는 동작이다(ADR 의 「기본 칸」). 지금의 「내 기본값」 설정 창은 이 동작으로 대신하고 지운다. 화면 계약 변경이므로 착수 때 코디네이터에게 한 번 알린다.
- `MEMBER` 역할에게는 칸 이름만 보인다. 칸 뒤의 provider, 모델, effort 는 관리자 영역에만 그린다(ADR-063).
- 저장하는 동안 보내기를 막는 규칙, 고를 에이전트가 없을 때 단추를 막는 규칙은 지금처럼 지킨다.

### 2. Control Plane

- 대화의 모델 선택 저장은 칸과 선택 없음만 받는다. provider, 모델, effort 를 싣는 요청과 `CUSTOM` 은 거절한다.
- 대화에 고르는 칸을 저장하면 사용자 기본 칸도 같은 트랜잭션에서 바꾼다.
- 이관 마이그레이션: `CUSTOM` 대화의 `model_selection_mode`, `model_provider`, `model`, `reasoning_effort` 를 비운다. 버전은 코디네이터의 예약 명령으로 받는다. 컬럼을 지울지는 실행 기록과 다시 생성이 읽는지 착수 때 확인해 정하고 PR 「결정」 에 적는다.
- 고를 수 있는 모델 목록을 사용자에게 주던 경로(`/api/chat/model-options` 계열)가 다른 곳에서 쓰이지 않으면 지운다.

### 3. 문서

- `docs/features/model-usage.md` 의 「요구」, 「흐름」, 「모델을 고를 때」, 「모델 선택」 을 슬라이더 기준으로 고친다. 지운 분기는 표에서 뺀다.
- ADR-030 의 상태 줄, `backend/docs/adr/INDEX.md` 의 상태 칸, ADR-20261101 / model-setting 의 상태 줄을 고친다.

## 검증

- 브라우저 테스트: 칸 슬라이더로 고른 값이 대화와 다음 새 대화에 남는다, 빈 칸은 보이지 않는다, 칸이 없으면 단추가 없다, `MEMBER` 는 칸 이름만 본다, 저장 중 보내기를 막는다.
- Control Plane 테스트: `CUSTOM` 요청 거절, 사용자 기본 칸 갱신, 이관 뒤 `CUSTOM` 대화가 기본 칸으로 돈다.
- 이 계획서는 이 PR 에서 지운다.
