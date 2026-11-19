# Phase 02. fos-ctx 가 만든 그림을 올리고 모델에게 가는 결과에서 경로를 뺀다

**Execution profile**: standard

## 목표

`image_generate` 호출에서 제목 줄을 떼고, 성공한 결과의 그림 파일을 Control Plane 에 올린 뒤 모델이 받는 결과를 경로 없는 글로 바꾼다.

**범위 외**: Control Plane 의 저장(Phase 01), 화면(Phase 03), 사람이 없는 실행에서 막기(스펙 PR2).

## 컨텍스트

- hook 의 동작과 결과 글, 제한 시간, 읽는 범위는 설계의 「fos-ctx 의 hook」 이 갖는다
- 서명과 Control Plane 호출은 `hermes/plugins/fos-ctx/handoff.py` 의 모양을 따른다. 주소는 `tool_guard.guard_url()` 의 마지막 조각을 `generated-image` 로 바꾼 것이다
- multipart 본문은 표준 라이브러리로 만든다. 새 의존을 더하지 않는다
- `pre_tool_call` 은 기존 도구 판정(`event_guard`)의 결과가 통과일 때만 제목 줄을 뗀다. 판정이 막으면 그 결과를 그대로 돌려준다
- Hermes 에 새로 기대는 동작 셋(결과의 `success` 와 `image`, 캐시 폴더, 내장 도구의 인자 병합)을 계약 문서와 계약 검사에 넣는다. 계약 검사는 `hermes/tests/test_tool_guard_contract.py` 처럼 `HERMES_SOURCE` 가 있을 때만 소스를 본다

**요구사항**: REQ1, REQ6, REQ10

**근거 문서**: `docs/features/generated-image/design.md`, `docs/adr/ADR-20261119-generated-image.md`, `hermes/docs/hermes-contract.md`

## 의도 메모

- hook 안의 모든 예외를 잡아 경로 없는 오류 결과로 바꾼다. 예외를 던지면 Hermes 가 원래 결과(경로)를 모델에게 준다
- 404 는 올리기 경로가 없는 옛 Control Plane 이다. 그때만 결과를 바꾸지 않는다
- 읽고 지우는 범위는 Hermes 홈의 `cache/generated/images` 아래 일반 파일이다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/generated_image.py`

제목 줄 떼기, 제목 기억(상한 있는 dict), 캐시 폴더 판정, multipart 만들기, 서명(`context.py` 에 `sign_generated_image` 추가), 올리기, 결과 글 만들기를 둔다.

### 2. `hooks.py` 와 `__init__.py`

`pre_tool_call` 의 내장 도구 경로에서 `image_generate` 가 판정을 통과하면 제목 줄을 뗀 `modify` 를 돌려준다. `transform_tool_result` 는 `image_generate` 를 먼저 보고 나머지는 지금 흐름을 그대로 둔다. plugin 의 버전을 올린다. 새 모듈을 `hermes/plugins/dashboard-profile-api/profiles.py` 의 `PROFILE_PLUGIN_FILES` 에 더한다. 빠지면 설치된 profile 에서 fos-ctx 가 import 에 실패한다.

### 3. 테스트

`hermes/tests/test_generated_image.py` 가 제목 줄 떼기와 없는 제목, 올리기 본문과 서명 확인 값(Phase 01 의 테스트와 같은 값), 성공과 실패와 404 의 결과 글, 캐시 폴더 밖 경로와 빈 `tool_call_id` 의 거절을 본다. `hermes/tests/test_generated_image_contract.py` 가 지정한 Hermes 소스에서 세 동작을 본다. `scripts/checks/check-hermes-contract.sh` 에 그 검사를 더한다.

### 4. 문서

`hermes/plugins/fos-ctx/README.md` 에 「만든 그림을 올린다」 절을, `hermes/docs/hermes-contract.md` 에 `image_generate` 의 결과 모양과 캐시 폴더, 내장 도구의 인자 병합을 적는다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_generated_image*.py'
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
python3 -m unittest discover -s hermes/tests
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/generated_image.py` | 신규 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/profiles.py` | 수정 (profile 에 설치할 파일 목록) |
| `hermes/plugins/fos-ctx/context.py` | 수정 |
| `hermes/plugins/fos-ctx/plugin.yaml` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_generated_image.py` | 신규 |
| `hermes/tests/test_generated_image_contract.py` | 신규 |
| `scripts/checks/check-hermes-contract.sh` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
