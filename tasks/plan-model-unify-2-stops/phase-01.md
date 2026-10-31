# Phase 01. 단계 셋을 ChatGPT 슬라이더 다섯 칸으로 바꾼다

**Execution profile**: standard

## 목표

모델 단계 `FAST`, `BALANCED`, `DEEP` 을 ChatGPT 의 Thinking 슬라이더 칸 `INSTANT`, `MEDIUM`, `HIGH`, `EXTRA_HIGH`, `PRO` 로 바꾼다.
화면에 보이는 칸 이름은 `Instant`, `Medium`, `High`, `Extra High`, `Pro` 로 옮기지 않고 그대로 쓴다.
기존 데이터는 가장 가까운 칸으로 옮기고, 관리자 「모델」 화면은 다섯 칸을 그룹 목록에서 고른다.

**범위 외**: 에이전트 기본 모델과 `DEFAULT` 모드 정리(`plan-model-unify-3-agent-follow`), 사용자 슬라이더와 `CUSTOM` 정리(`plan-model-unify-4-slider`).

## 컨텍스트

- 결정과 이관 표: `docs/adr/ADR-20261101-model-setting.md` 의 「결정」. 칸 이름, 순서, 이관 대응은 그 표가 소유한다. 이 문서에 옮겨 적지 않는다.
- 지금 동작: `docs/features/model-usage.md` 의 「모델 단계와 실행 기록」 과 「그룹 모델 목록」.
- 단계 enum 은 `backend/src/main/java/com/bifos/assistant/model/domain/type/ModelTier.java`, 칸 이름은 `ModelTierService` 가 붙인다. 화면 쪽 코드는 `web/src/lib/model-tiers.ts` 의 `ModelTierCode` 다.
- 단계 값이 들어 있는 컬럼은 `model_tier_definition.tier`, `model_tier_group_setting.default_tier`, `app_user.model_default_tier`, `conversation.model_tier`, `task.model_tier` 다. 모두 `VARCHAR(16)` 이라 `EXTRA_HIGH` 가 들어간다. 착수 때 `backend/docs/data-schema.md` 와 마이그레이션에서 다시 확인한다.
- `agent_execution.model_tier` 는 그때 고른 값의 기록이라 옮기지 않는다. 화면은 옛 이름도 그린다.
- 테이블을 새로 만들지 않는다(사용자 결정 2026-11-01).

## 작업 항목

### 1. 이관 마이그레이션

- 버전은 코디네이터의 예약 명령으로 받는다. 파일 이름을 직접 정하지 않는다.
- 위 컬럼의 `FAST`, `BALANCED`, `DEEP` 을 ADR 의 이관 표대로 바꾼다. DML 이므로 `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 에 따라 DDL 과 나눈다.
- 그룹 정의 행이 있는 그룹은 `EXTRA_HIGH`, `PRO` 를 빈 mapping 행으로 더한다. 정의 행이 없는 그룹은 그대로 둔다(행이 없으면 모든 칸이 빈 mapping 이다).
- 제품 코드와 마이그레이션에 모델 이름을 넣지 않는다(「모델 단계와 실행 기록」).

### 2. Control Plane

- `ModelTier` 를 다섯 값으로 바꾸고 칸 이름을 ChatGPT 이름으로 붙인다.
- 옛 값으로 오는 요청(배포 중 옛 화면)은 `VALIDATION_FAILED` 로 거절한다. 옛 값을 새 값으로 몰래 바꾸지 않는다.
- 그룹 정의 저장이 다섯 칸 모두를 한 번씩 받는지 검사하는 규칙을 다섯으로 맞춘다.
- **바뀐 칸만 그룹 목록과 비교한다.** 저장된 값과 다른 provider·모델이면 `GroupModelCatalogService` 의 목록에 있어야 하고, 없으면 캐시를 건너뛰고 한 번 다시 읽은 뒤에도 없을 때 `VALIDATION_FAILED` 다. 바뀌지 않은 칸은 목록과 비교하지 않는다. 목록 조회는 트랜잭션 밖에서 한다(`AgentModelDefaultService.save` 의 주석과 같은 까닭).
- 바뀐 칸은 provider 를 비우고 모델만 둘 수 없다. 옛 정의의 빈 provider 는 그대로 둔다.
- 실행 기록 화면의 단계 이름 표에 옛 이름(빠르게, 균형, 깊게)을 남긴다.

### 3. 화면

- `web/src/lib/model-tiers.ts` 의 `ModelTierCode` 와 칸 목록을 바꾼다.
- 관리자 「모델」 화면은 칸 다섯을 순서대로 그린다. 고르는 칸은 첫 PR 의 `group-tier-settings.tsx` 를 그대로 쓴다.
- 지금의 사용자 단계 고르기(`model-picker.tsx`)는 칸 이름만 바꾸고 모양은 `plan-model-unify-4-slider` 가 바꾼다. 빈 칸은 고르는 목록에서 뺀다(ADR 의 「칸의 값」).

### 4. 문서

- `docs/features/model-usage.md` 의 「모델 단계와 실행 기록」 과 「모델 선택」 표를 다섯 칸으로 고친다.
- `docs/features/schedule.md` 의 「모델 단계」 를 확인해 칸 이름을 맞춘다.
- ADR 의 상태 줄에 이 단계가 구현됐다고 적는다.

## 검증

- 마이그레이션 테스트가 옛 값이 든 행을 새 값으로 바꾸고, 정의 행이 있는 그룹에 두 칸을 더하는지 본다. MySQL 검사는 `scripts/checks/check-mysql-migration.sh` 로 돌린다.
- `ModelTierService` 테스트: 다섯 칸 저장, 옛 값 거절, 바뀐 칸만 목록과 비교, 목록을 읽지 못해도 바뀌지 않은 칸은 저장된다.
- 브라우저 테스트: 관리자 화면의 다섯 칸, `test/browser/admin-model-tiers.spec.ts` 의 목록 없음과 목록에 없는 값 경우를 다섯 칸에 맞춘다. `test/browser/model-choice.spec.ts` 의 칸 이름을 바꾼다.
- 이 계획서는 이 PR 에서 지운다.
