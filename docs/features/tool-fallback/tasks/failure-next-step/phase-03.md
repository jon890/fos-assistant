# Phase 03. fos-ctx 의 실패 글이 끝 줄 하나로 끝난다

**Execution profile**: standard

## 목표

fos-ctx 가 모델에게 직접 쓰는 실패 글(그림 바탕 사진 받기, 원본 사진 조회, 그림 저장, 위험 명령·승인 경로 막기, 판정을 받지 못한 막기, 하위 에이전트 사진 경로)이 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 로 끝나게 한다. 링크 사진 받기 실패 글이 「사진을 직접 올려 달라고 한다」 만 말하지 않게 한다.

**범위 외**: Control Plane 이 보낸 판정 글은 Control Plane 이 끝 줄을 붙여 보내므로 fos-ctx 가 덧붙이지 않는다(`connector_policy.py:152-154`, `tool_guard.py:99-101` 의 전달은 그대로). 링크 사진 후보 넓히기와 후보 알림 한 줄은 PR2. 성공 글(`SAVED_MESSAGE`)과 `EXTERNAL_DATA_NOTICE` 는 실패 글이 아니다.

## 컨텍스트

fos-ctx 는 Hermes plugin 이고 모듈끼리 상대 import 를 쓴다(`from .connector_policy import _block`). 새 모듈도 `from .next_step import alternative, ask_user` 로 쓴다.
실패 글 상수는 아래에 있다.

| 모듈 | 상수 |
| --- | --- |
| `image_source.py` | `NOT_REFERENCE`, `TOO_MANY`, `UNKNOWN`, `GONE`, `LINK_REASONS` 의 넷, `FAILED`, `OLD_SERVER`, `NO_CONTEXT`(`HEAD`, `TAIL` 은 글을 잇는 틀) |
| `attachment_inspect.py` | `FAILURE`, `handle` 안의 `region_required` JSON `error` 글 |
| `generated_image.py` | `FAILED_MESSAGE` |
| `approval_guard.py` | `DANGEROUS_COMMAND_MESSAGE`, `APPROVAL_WRITE_MESSAGE`, `COMPUTER_USE_MESSAGE` |
| `connector_policy.py` | `POLICY_BLOCK_MESSAGE`, `CONTEXT_BLOCK_MESSAGE`, `UNKNOWN_SERVER_MESSAGE`, `ARGS_TOO_LARGE_MESSAGE`, `CODE_EXECUTION_MESSAGE` |
| `tool_guard.py` | `GUARD_BLOCK_MESSAGE` |
| `hooks.py` | `SKILL_MANAGE_MESSAGE`, `DELEGATE_IMAGE_MESSAGE`, `BLOCK_MESSAGE` |

`image_source.message(problems)` 는 `HEAD`, 문제마다 `- <글>` 한 줄, `TAIL` 을 이어 붙인다. 문제 글에 끝 줄이 붙으면 여러 줄이 되므로 이어지는 줄을 들여 쓴다. 글 전체가 끝 줄로 끝나도록 `TAIL` 을 없애고 「그림을 만들지 않았다」 를 `HEAD` 로 옮긴다(설계 「바꾸는 실패 글」 의 `image_source.py` 행).
테스트는 `hermes/tests/` 의 unittest 이고 `test_fos_ctx.py` 의 `load_ctx` 가 plugin 을 읽는다. 기존 테스트는 상수를 `self.plugin.<이름>` 으로 비교하므로 값이 바뀌어도 대개 그대로 통과한다.

**요구사항**: REQ2, REQ3

**근거 문서**: `docs/features/tool-fallback/spec.md` 의 REQ2, REQ3, DECISION8, DECISION9, DECISION10, `docs/features/tool-fallback/design.md` 의 「실패 글의 끝 줄」 과 「바꾸는 실패 글」 의 fos-ctx 행

## 의도 메모

- 원본 사진 조회 실패는 재업로드를 요구하지 않는 지금 뜻을 지킨다(스펙 DECISION8)
- 위험 명령과 화면 조작은 막힌 대상 대신 막히지 않은 다른 명령·도구로 같은 일을 하게 한다. 같은 대상을 셸이나 코드 실행으로 우회하게 하지 않는다(스펙 DECISION9)
- 링크 사진 받기 실패(`NO_IMAGE`, `FAILED`)의 다음 방법은 「다른 방법이 없으니 해 본 것을 알리고 사진을 올려 달라고 묻는다」 다. 같은 링크를 다시 부르게 하지 않는다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/next_step.py`(신규)

- `NEXT_PREFIX = "다음 방법: "`, `ASK_USER = "사용자에게 바로 알린다."`. Java `ToolFailureText` 와 같은 글이다(파일 맨 위 설명에 그 사실을 적는다)
- `alternative(message: str, next_step: str) -> str`: `message.rstrip() + "\n" + NEXT_PREFIX + next_step`. 빈 `next_step` 은 `ValueError`
- `ask_user(message: str) -> str`: `message.rstrip() + "\n" + ASK_USER`
- `ends_with_step(text: str) -> bool`: 앞뒤 공백을 걷어 낸 마지막 줄이 `NEXT_PREFIX` 로 시작하거나 `ASK_USER` 와 같은지. 테스트가 쓴다

### 2. `image_source.py`

- `LINK_REASONS`
  - `IMAGE_SOURCE_LINK_BLOCKED`: `ask_user("열 수 없는 주소다")`(스펙 DECISION9)
  - `IMAGE_SOURCE_LINK_NO_IMAGE`: `alternative("이 링크에서 쓸 수 있는 사진을 찾지 못했다", "다른 방법이 없으니 해 본 것을 알리고 사진을 직접 올려 달라고 묻는다")`
  - `IMAGE_SOURCE_LINK_FAILED`: `alternative("링크를 열지 못했다. 쇼핑몰이 막았을 수 있다", 같은 다음 방법)`
  - `IMAGE_SOURCE_LINK_TOO_LARGE`: `alternative("사진이 너무 크다", "다른 방법이 없으니 해 본 것을 알리고 더 작은 사진을 올려 달라고 묻는다")`
- `FAILED`: `alternative("사진을 받지 못했다", "다른 방법이 없으니 잠시 뒤 다시 해 달라고 알린다")`(스펙 DECISION10)
- `NOT_REFERENCE`: 다음 방법 `attachment:, generated:, link: 가리킴으로 고쳐 다시 부른다`
- `UNKNOWN`: 다음 방법 `이 대화의 첨부, 사용자가 보낸 링크, 만든 그림 가운데 맞는 가리킴을 찾아 다시 부른다`
- `TOO_MANY`: 다음 방법 `사진을 %d장 이하로 줄여 다시 부른다`. 「사용자에게 줄여 달라고 한다」 는 뺀다
- `NO_CONTEXT`: 다음 방법 `대화에서 직접 image_generate 를 부른다`
- `GONE`, `OLD_SERVER`: `ask_user`
- `HEAD`: `"fos-ctx: 바탕이나 참고로 가리킨 사진을 쓸 수 없어 그림을 만들지 않았다. 줄마다의 다음 방법이나 바로 알림을 따른다."`
- `TAIL` 을 지운다. 「사용자에게 어느 사진을 왜 쓸 수 없는지 알린다」 는 문제 줄마다의 끝 줄이 대신한다
- `message(problems)`: `HEAD` 뒤에 문제마다 `- <글>` 을 두고, 문제 글의 둘째 줄부터 두 칸 들여 쓴다. 글 전체는 마지막 문제의 끝 줄로 끝난다

### 3. 나머지 모듈

- `attachment_inspect.py`: `FAILURE = ask_user("원본 사진을 받지 못해 판독하지 못했다. 재업로드를 요구하지 말고 실패를 알린다.")`. `handle` 의 `region_required` JSON 의 `error` 글은 「전체 원본의 안전 한도를 넘었다.」 를 앞에, 지금의 영역 지정·overview·3회 안내를 `alternative` 의 다음 방법으로 옮긴다. 이 글을 모듈 상수 `REGION_REQUIRED` 로 빼서 테스트가 모을 수 있게 한다
- `generated_image.py`: `FAILED_MESSAGE = ask_user(지금 글)`. 「잠시 뒤 다시 부탁하라고 안내한다」 는 사용자에게 하는 안내라 그대로 둔다
- `approval_guard.py`: `DANGEROUS_COMMAND_MESSAGE`, `COMPUTER_USE_MESSAGE` 는 `alternative("이 명령은 위험 명령으로 분류돼 실행하지 않는다. 다시 부르지 않는다.", "막히지 않은 다른 명령이나 도구로 같은 일을 한다")` 꼴이다(화면 조작도 같은 꼴로 앞 문장만 다르다). `APPROVAL_WRITE_MESSAGE` 는 `ask_user("이 경로는 쓰려면 사람의 승인이 필요해 쓰지 않는다. 다시 부르지 않고 셸이나 코드 실행으로 우회하지도 않는다.")`
- `connector_policy.py`: `POLICY_BLOCK_MESSAGE` 는 `alternative("fos-ctx: 이 도구 호출의 사용 정책을 확인하지 못해 막았다.", "다른 도구가 있으면 그것으로 해 보고, 없으면 잠시 뒤 다시 해 달라고 알린다")`. `ARGS_TOO_LARGE_MESSAGE` 는 다음 방법 `인자를 나눠서 다시 부른다`. `CODE_EXECUTION_MESSAGE` 는 다음 방법 `코드 실행 밖에서 그 도구를 직접 부른다`. `CONTEXT_BLOCK_MESSAGE`, `UNKNOWN_SERVER_MESSAGE` 는 `ask_user`
- `tool_guard.py`: `GUARD_BLOCK_MESSAGE` 는 `POLICY_BLOCK_MESSAGE` 와 같은 꼴의 다음 방법
- `hooks.py`: `DELEGATE_IMAGE_MESSAGE` 는 다음 방법 `HTTP(S) 주소나 이미지 data URL 로 바꿔 넘긴다`. `SKILL_MANAGE_MESSAGE`, `BLOCK_MESSAGE` 는 `ask_user`
- `hermes/plugins/dashboard-profile-api/profiles.py` 의 `PROFILE_PLUGIN_FILES` 에 `"next_step.py"` 를 더한다. 갱신 설치가 이 목록으로 파일을 옮기므로 빠지면 운영에서 `from .next_step import` 가 깨진다. `test_dashboard_profile_api_connector_install_plugin.py` 가 목록과 fos-ctx 의 `*.py` 가 같은지 본다
- `__init__.py` 가 상수를 다시 내보내는 목록이 있으면 그대로 두고, 새 모듈을 내보낼 필요는 없다

### 4. 문서

- `hermes/plugins/fos-ctx/README.md` 의 「그림을 고칠 사진을 받는다」 표에서 모델이 받는 글 칸을 새 글로 맞추고, 실패 글이 끝 줄 하나로 끝난다는 한 줄과 `next_step.py` 를 적는다
- `docs/features/generated-image/design.md` 의 받기 실패 표(`IMAGE_SOURCE_LINK_NO_IMAGE`, `IMAGE_SOURCE_LINK_FAILED` 행)의 모델이 받는 글 칸을 새 글로 맞춘다

### 5. 테스트 `hermes/tests/test_fos_ctx_next_step.py`(신규)

- `load_ctx` 로 plugin 을 읽고 위 표의 모든 실패 글 상수와 `LINK_REASONS` 값, `REGION_REQUIRED` 를 모아. plugin 이 다시 내보내지 않는 상수는 `sys.modules[plugin.__name__ + ".<모듈>"]` 에서 읽는다(`test_image_source.py` 의 `links_for` 읽기가 본보기다). 그 값들을 `ends_with_step` 이 참인지 본다.
- 어느 글도 「올려 달라고 한다」 로 끝나지 않는다(사용자에게 넘기라고만 하는 글이 없다)
- `alternative` 의 빈 다음 방법이 `ValueError` 다
- `image_source.message` 가 끝 줄이 붙은 문제 글 둘을 받아 이어지는 줄을 들여 쓰고, 글 전체가 `ends_with_step` 참이다(마지막 문제의 끝 줄로 끝난다)
- `test_fos_ctx.py` 에서 `SKILL_MANAGE_MESSAGE` 를 문자열 그대로 비교하는 곳(335행 근처)을 상수 비교로 바꾼다
- 기존 `test_image_source.py`, `test_fos_ctx.py`, `test_attachment_inspect.py`, `test_generated_image.py` 에서 글을 문자열로 직접 비교하던 곳을 맞춘다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx*.py'
python3 -m unittest discover -s hermes/tests -p 'test_image_source.py'
python3 -m unittest discover -s hermes/tests -p 'test_attachment_inspect*.py'
python3 -m unittest discover -s hermes/tests -p 'test_generated_image*.py'
python3 -m unittest discover -s hermes/tests
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

기대값: 모두 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/next_step.py` | 신규 |
| `hermes/plugins/fos-ctx/image_source.py` | 수정 |
| `hermes/plugins/fos-ctx/attachment_inspect.py` | 수정 |
| `hermes/plugins/fos-ctx/generated_image.py` | 수정 |
| `hermes/plugins/fos-ctx/approval_guard.py` | 수정 |
| `hermes/plugins/fos-ctx/connector_policy.py` | 수정 |
| `hermes/plugins/fos-ctx/tool_guard.py` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/profiles.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `docs/features/generated-image/design.md` | 수정 |
| `hermes/tests/test_fos_ctx_next_step.py` | 신규 |
| `hermes/tests/test_image_source.py` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
