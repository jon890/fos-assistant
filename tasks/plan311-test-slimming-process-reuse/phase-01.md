# Phase 01. 금융 plugin 프로세스를 클래스마다 한 번만 띄운다

**Execution profile**: deep

## 목표

금융 producer 왕복 검사 다섯 클래스(134건)가 건마다 띄우는 Python plugin, bun 거래 대역, bun SDK 생성을 클래스마다 한 번으로 줄이고, guard 왕복 검사(25건)도 같은 방식으로 바꾼다.
추정으로 unit 테스트 실행이 약 4분, MySQL 이 약 2분 줄어든다. 고정 비용은 건당 약 1.75초다.

**범위 외**: 같은 검사를 H2 와 MySQL 에서 되풀이하는 구조(phase 02), image 캐시(phase 03).

## 컨텍스트

**근거 문서**: `docs/features/tossinvest-connector.md`, `backend/docs/adr/ADR-20261007-test-context-base.md` 의 「MySQL 검사」

- 선행: 금융 claim 상한 변경(#611)이 main 에 머지돼 있어야 한다. `connector/**` 검사를 함께 고치면 충돌한다.
- `FinancialConnectorTestBase` 의 `@BeforeEach` 가 `hermes/connectors/tossinvest` 의 `producer_plugin_http.py` 를 띄운다. 그 스크립트는 기동할 때 bun 거래 대역을 띄우고 bun 으로 SDK 원고를 생성한다.
- 스크립트에는 이미 `/fixture/configure` 가 있어 profile 과 카운터를 처음 상태로 되돌린다.

## 의도 메모

- 앞 검사의 상태가 Python 쪽에 남는 것이 위험이다. `/fixture/configure` 가 되돌리지 않는 상태를 먼저 찾아 목록으로 남기고, 되돌리지 못하는 상태가 있으면 그 상태를 쓰는 검사는 건마다 띄우는 쪽에 둔다.
- 덜 잡게 되는 것: 프로세스 기동 자체를 건마다 확인하던 효과가 없어진다. 기동 실패는 클래스의 첫 검사 하나가 잡는다.

## 작업 항목

### 1. 기반 클래스의 프로세스 수명

`FinancialConnectorTestBase` 의 기동을 `@BeforeAll`(또는 `PER_CLASS` 인스턴스)로 옮기고 `@BeforeEach` 에서는 `/fixture/configure` 로 되돌리기만 한다. 종료는 `@AfterAll` 이다. 같은 방식으로 `ConnectorGuardBindingRoundTripTest` 의 guard plugin 기동을 옮긴다.

### 2. 되돌림 검증

`/fixture/configure` 호출 뒤 대역 카운터와 profile 이 처음과 같다는 것을 확인하는 검사를 기반 클래스 옆에 더하고, 앞 검사가 남긴 상태가 다음 검사의 결과를 바꾸지 않는지 순서를 뒤집어 한 번 돌린다.

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialConnectorProducer*' --tests '*FinancialOrderOpeningTest' --tests '*FinancialLimitChangesTest' --tests '*ConnectorGuardBindingRoundTripTest'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/FinancialConnectorTestBase.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingRoundTripTest.java` | 수정 |
