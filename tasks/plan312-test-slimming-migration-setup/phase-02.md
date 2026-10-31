# Phase 02. 운영에 적용된 옛 마이그레이션의 데이터 검사를 정리한다

**Execution profile**: standard

## 목표

운영에 이미 적용된 마이그레이션의 데이터 변환 검사를 정리하고, `backend/docs/data-schema.md` 의 규칙에 보존 기간을 더한다.
추정으로 setup 을 줄이기 전 기준으로 MySQL 이 약 3.5분, unit 이 약 0.5분 줄고, phase 01 뒤에는 MySQL 이 약 1.5분 준다.

**범위 외**: 스키마를 대조하는 `MysqlMigrationTest`, 정렬 규칙 검사(`CollationUnifyMysqlMigrationTest` 등).

## 컨텍스트

**근거 문서**: `backend/docs/data-schema.md` 의 「실제 MySQL 검사를 통과해야 한다」

- 적용된 마이그레이션 파일은 `test/unit/migration-immutable.test.ts` 와 Flyway checksum 이 바뀌지 않게 막는다. 그 파일의 데이터 변환은 운영에서 이미 한 번 돌았고, 새로 설치하는 DB 에는 옮길 줄이 없다. 이 검사가 새로 잡는 것은 H2, MySQL, Flyway 버전을 올렸을 때의 동작 차이뿐이다.
- 어느 버전까지 운영에 적용됐는지는 이 저장소가 알 수 없다. 정리 기준은 운영 저장소의 배포 기록과 맞춰야 하므로 착수 전에 coordinator 에게 기준 버전을 요청한다.

## 의도 메모

- 위험은 그 마이그레이션이 장애나 리뷰 지적에서 생긴 경우다. `SubagentUsageLedgerMysqlMigrationTest` 는 운영의 오류 1267 에서 생겼다. 그 교훈은 `MysqlMigrationTest` 의 정렬 규칙 검사와 `data-schema.md` 의 규칙으로 일반화돼 있어, 이 검사를 지워도 같은 종류의 실패를 잡는다.
- 보존 기간은 「배포 뒤 N 개 릴리스」 같은 기준으로 규칙에 적는다. 기준 값은 사용자가 정한다.

## 블로커 조건

- 기준 버전을 받지 못하면 멈추고 보고한다.

## 작업 항목

### 1. 규칙에 보존 기간 더하기

`data-schema.md` 의 「줄을 넣거나 고치는 마이그레이션을 쓰면 그 검사를 실제 MySQL 에서도 돌린다」 에 보존 기간과 지우는 절차를 더한다.

### 2. 기준 버전 이전 검사 정리

기준 버전 이전 마이그레이션의 `*MigrationTest` 와 `*MysqlMigrationTest` 를 지우고, 지운 목록을 PR 본문에 둔다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MigrationTest'
scripts/checks/check-mysql-migration.sh
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/docs/data-schema.md` | 수정 |
