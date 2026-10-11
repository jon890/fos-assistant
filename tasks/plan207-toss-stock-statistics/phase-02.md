# Phase 02. 운영 등록, 설명, READ 정책과 coverage

**Execution profile**: standard

## 목표

5개 통계 조회를 production 서버에 연결하고 공식 coverage와 도구 설명, READ 정책을 같은 작동 PR에서 완성한다.
현재 worker는 이 단계를 실행하지 않는다.
모듈 시험 통과만으로 계획을 완료하거나 지우지 않는다.

**범위 외**: 금융 주문 실행, 공용 계약 변경, 새 의존, 실제 token과 계좌, 배포와 운영 제약 변경.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「공식 operation과 producer」, 「요청 경계와 시험」과 `docs/features/connector-policy.md`.
main의 5개 통계 도구는 미연결이다.
다른 작업 공간의 `stock-trend-tools.ts`가 통합되었으면 같은 도구명을 중복 등록하지 않고 종목 통계 등록을 한 곳으로 정리한다.
사용할 신규 함수는 `registerStockStatisticsTools(register, client)`다.
공식 원문은 [OpenAPI JSON](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json) 1.2.24와 [개요](https://openapi.tossinvest.com/openapi-docs/overview.md)다.

## 작업 항목

### 1. 서버와 도구 설명

`createTossinvestServer`에서 `registerStockStatisticsTools`를 한 번 호출한다.
`createToolRegistration`의 description 목록에 5개 이름과 국내 전용, count, until, nextUntil, 단위와 데이터 시점 설명을 추가한다.
투자자는 KRX와 NXT 통합, 프로그램은 KRX만이라는 차이를 명시한다.
공매도 비중의 null과 0, nullable 신용 객체, 개인 신용대주와 기관 대차의 차이를 설명한다.
장중 확정치나 모든 시장 지원을 보장하지 않는다.

### 2. 정책과 문서

`connector.json`에 5개를 READ/none으로 선언한다.
README와 SKILL에 조회 예시와 페이지, 단위, KR 범위, 반영 시점, unsupported-market 오류를 적는다.
`docs/features/connector.md`의 실제 구현 상태를 갱신하고 종목 통계 계약 절을 작성한다.
`docs/features/connector-policy.md`의 기존 READ 판정 계약을 적용하며 금융 승인 정책을 바꾸지 않는다.

### 3. coverage와 통합 시험

`CURRENT_OPERATIONS`에 이번 5개를 추가하고 `rest-coverage.test.ts`의 실제 등록 연결을 검사한다.
운영 서버 SDK의 tools/list에 5개가 정확히 한 번 있고 description에 undefined가 없는지 확인한다.
실제 운영 등록에서 tools/call이 FakeToss의 5개 GET에 도달하며 정책과 annotation이 맞는지 시험한다.
모듈 시험과 전체 기존 회귀를 함께 실행한다.
최종 bundle 시험은 통합 담당자가 기존 build 경로에서 수행한다.
완료된 설계는 기능 문서에 남기고 이 계획은 구현 PR에서 삭제한다.

## 검증

```bash
cd hermes/connectors/tossinvest && bun test ./src ./tests ./scripts
cd hermes/connectors/tossinvest && bun run typecheck
cd hermes/connectors/tossinvest && bun run build && bun run check:bundle
node scripts/format-connectors.mjs check
node --experimental-strip-types --test test/unit/doc-files.test.ts
bash scripts/check-public-safe.sh
git diff --check
```

무거운 검사는 heavy-lock으로 checkout별 순차 실행한다.
최종 설명, READ 선언, 기능 문서와 공식 operation coverage가 모두 맞아야 전체 계획 완료다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/connectors/tossinvest/src/server.ts` | 수정 |
| `hermes/connectors/tossinvest/src/tool-registration.ts` | 수정 |
| `hermes/connectors/tossinvest/src/api-contract.ts` | 수정 |
| `hermes/connectors/tossinvest/connector.json` | 수정 |
| `hermes/connectors/tossinvest/README.md` | 수정 |
| `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` | 수정 |
| `hermes/connectors/tossinvest/tests/rest-coverage.test.ts` | 수정 |
| `docs/features/connector.md` | 수정 |
