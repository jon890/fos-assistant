# Phase 02. hermes-tests 를 두 job 으로 나눈다

**Execution profile**: standard

## 목표

`hermes-tests` job(약 8.6분)을 커넥터 묶음 검사와 Python unittest 두 job 으로 나눠 약 4.5분으로 줄인다.

**범위 외**: 각 검사 내부의 병렬화(phase 03).

## 컨텍스트

**근거 문서**: `.github/workflows/ci.yml`, `docs/adr/ADR-20261012-ci-path-filter.md`

- `check-connector-package.sh` 는 250초, `python -m unittest discover` 는 211초, `check-hermes-contract.sh` 는 36초다.
- 필수 검사 이름 `hermes` 를 그대로 두려면 판정 job 이 두 job 을 기다려야 한다. runner 를 하나 더 쓰므로 동시 job 한도와 맞는지 사용자가 정한다.

## 의도 메모

- 필수 검사 이름이 바뀌면 브랜치 보호가 깨진다. 판정 job 의 이름을 바꾸지 않는다.

## 작업 항목

### 1. job 분할

`hermes-tests` 를 `hermes-connectors` 와 `hermes-python` 으로 나누고 `hermes` 판정 job 의 `needs` 에 둘을 넣는다.

## 검증

```bash
node --test test/unit/ci-paths.test.ts
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `.github/workflows/ci.yml` | 수정 |
