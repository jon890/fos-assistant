# Phase 01. 모든 에이전트가 그룹 모델 설정을 따르게 한다

**Execution profile**: standard

## 목표

에이전트 기본 모델을 없애고, 고르지 않은 대화와 대화 없는 실행이 모두 그룹 모델 설정으로 돌게 한다.
관리자가 그룹 설정을 한 번 바꾸면 모든 에이전트의 다음 실행이 그 값으로 돈다.

**범위 외**: 사용자 슬라이더와 `CUSTOM` 정리(`plan-model-unify-4-slider`).

## Blocked 조건

- `plan-model-unify-2-stops` 가 main 에 머지돼 있어야 한다. 칸 코드가 바뀐 뒤의 해석을 고친다.

## 컨텍스트

- 결정: `docs/adr/ADR-20261101-model-setting.md` 의 「에이전트 기본 모델」, 「기존 값은 이렇게 옮긴다」.
- 지금 동작: `docs/features/model-usage.md` 의 「모델 선택」 표, 「에이전트 기본 모델」, 「요청에 모델을 싣지 않는 실행」.
- 코드: `AgentModelDefaultService`, `ModelAdminController` 의 `/agents/{code}/model-settings` 와 `/agents/{code}/model-default`, `ModelTierService` 의 해석과 `detachedChoice`, `ModelOptionsService.optionsOf` 의 에이전트 기본값, 화면의 에이전트 상세 「모델」 절과 `web/src/lib/model-settings.ts`.
- `agent` 테이블의 `default_model_provider`, `default_model`, `default_reasoning_effort` 가 지울 값이다.

## 작업 항목

### 1. 해석

- 선택 없는 대화의 차례는 사용자 기본 칸, 그룹 기본 칸이다. 둘 다 없거나 고른 칸이 비었으면 요청에 모델을 싣지 않아 Hermes profile 의 값으로 돈다.
- `DEFAULT` 모드를 없앤다. 대화를 `DEFAULT` 로 저장하는 요청은 거절하고, 저장된 `DEFAULT` 는 이관에서 선택 없음으로 바꾼다.
- 대화 없이 도는 실행(추천 질문, 예약 작업, 위임)도 같은 차례로 정한다. 지금 `detachedChoice` 가 에이전트 기본값을 싣는 자리다.
- 실행 기록의 effort 출처 `AGENT_DEFAULT` 는 새 실행에 더는 쓰지 않는다. 옛 기록의 값은 그대로 둔다(`docs/features/model-usage.md` 「profile 기본 강도」).

### 2. 지우기

- `AgentModelDefaultService` 와 관리 경로 둘, 에이전트 상세의 「모델」 절을 지운다.
- 「모델 숨김」 절의 에이전트 고르기는 그룹 목록(`GroupModelCatalogService`)으로 바꾼다. 숨김은 `plan-model-unify-2-stops` 뒤로 `group_setting` 의 `MODEL` JSON 안에 있다. 숨김 후보는 교집합이 아니라 각 profile 목록의 합집합이 맞는지 착수 때 판단하고 PR 「결정」 에 적는다. 숨길 것은 어느 profile 에 있든 숨겨야 하기 때문이다.
- 마이그레이션으로 `agent` 테이블의 세 컬럼을 지운다. 버전은 코디네이터의 예약 명령으로 받는다. 값은 옮기지 않는다(ADR). **이 컬럼 삭제는 되돌리기 어려운 데이터 삭제다.** 착수 때 코디네이터에게 운영의 현재 값 목록을 요청해 PR 본문의 「결정」 에 남겨 관리자가 그룹 설정으로 다시 정할 수 있게 한다.

### 3. 문서

- `docs/features/model-usage.md` 의 「에이전트 기본 모델」 절을 지우고, 「모델 선택」 표와 「요청에 모델을 싣지 않는 실행」 을 고친다.
- `backend/docs/data-schema.md` 의 `agent` 절에서 세 컬럼을 지운다.
- ADR-054 의 상태 줄과 `backend/docs/adr/INDEX.md` 의 상태 칸을 고친다.

## 검증

- `ModelTierService` 테스트: 그룹 기본 칸으로 도는 새 대화, 빈 칸이면 모델을 싣지 않는 실행, `DEFAULT` 요청 거절, 대화 없는 실행의 해석.
- 숨김 판정 테스트: 에이전트 기본값이 없어진 뒤 「요청에 모델을 싣지 않는 실행」 표의 경우가 그대로 판정되는지.
- 브라우저 테스트: `test/browser/agent-model.spec.ts` 를 지우거나 그룹 설정으로 옮기고, 관리자 화면의 숨김 절을 확인한다.
- 이 계획서는 이 PR 에서 지운다.
