# Phase 03. hermes 검사 내부를 병렬로 돌리고 실제로 기다리는 시간을 줄인다

**Execution profile**: standard

## 목표

Python unittest 를 모듈별로 나눠 3-4개 프로세스에서 돌리고(약 2분), 커넥터 bun 테스트의 429 재시도 대기와 timeout 을 주입한 짧은 값으로 바꾼다(약 1분). 코드 규칙 테스트의 위반 fixture 컴파일을 한 번으로 묶는다(약 20-30초).

**범위 외**: 코드 규칙 테스트를 지우는 일. 지우지 않는다.

## 컨텍스트

**근거 문서**: `hermes/AGENTS.md`, `backend/docs/code-architecture.md` 의 「구조 규칙」

- bun 테스트 1439건 가운데 1초 이상인 73건이 118초를 차지한다. 429 재시도 35건이 실제로 1초를 기다리고 Chrome 시작 timeout 하나가 10초다.
- 같은 임시 경로나 환경 변수를 쓰는 Python 테스트가 겹칠 수 있다.

## 의도 메모

- 실제 대기 시간이 맞는지는 다른 테스트가 봐야 한다. 대기 시간 계산 자체를 확인하는 검사는 남긴다.

## 작업 항목

### 1. Python unittest 분할

모듈 묶음마다 프로세스를 나눠 돌리고 겹치는 임시 경로를 찾아 고친다.

### 2. 대기 주입

재시도 간격과 시작 timeout 을 주입할 수 있게 하고 테스트에서 짧은 값을 준다.

### 3. fixture 컴파일 묶기

`ArchitectureRules` 의 위반 fixture 를 규칙마다 javac 로 컴파일하지 않고 한 번에 묶어 컴파일한다.

## 검증

```bash
cd backend && ./gradlew archTest
python3 -m unittest discover -s hermes/tests
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `.github/workflows/ci.yml` | 수정 |
