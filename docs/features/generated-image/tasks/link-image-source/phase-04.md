# Phase 04. fos-ctx 가 link: 를 받아 넘기고 쓴 링크를 그림과 함께 올린다

**Execution profile**: standard

## 목표

fos-ctx 의 `image_generate` hook 이 `link:` 가리킴을 받아들이고, 새 오류 코드를 까닭 글로 바꾸며, 받은 링크를 그림 올리기의 `source_links` 로 보낸다. 마지막 phase 라 phase 03 이 미룬 브라우저 테스트도 여기서 돌린다.

**범위 외**: Control Plane 과 화면 코드(phase 01 부터 phase 03 까지).

## 컨텍스트

**요구사항**: REQ20, REQ21, REQ22, REQ23

**근거 문서**: `docs/features/generated-image/design.md` 의 「PR5」, `docs/adr/ADR-20261120-link-image-source.md`, `hermes/plugins/fos-ctx/README.md` 의 「그림을 고칠 사진을 받는다」

- 가리킴 받기는 `hermes/plugins/fos-ctx/image_source.py` 다. `_is_reference` 가 두 접두어만 받고, `_fetch` 가 오류 `code` 를 `UNKNOWN`, `GONE`, `FAILED` 로 바꾸며, `_remember`, `release` 가 `(session_id, tool_call_id)` 마다 사본을 기억하고 지운다. `NOT_REFERENCE`, `UNKNOWN` 글이 「주소는 쓰지 않는다」, 「사진이나 그림이 아니다」 다.
- 올리기는 `hermes/plugins/fos-ctx/generated_image.py` 의 `sign_generated_image` 와 결과 hook 의 `meta` 다. 결과 hook 에서 받는 인자는 이미 사본 경로로 바뀐 인자라 링크를 다시 알 수 없다. 그래서 `image_source` 가 기억한다.
- 플러그인 버전과 README 의 버전 표기 방식은 `hermes/plugins/fos-ctx/plugin.yaml` 과 README 의 기존 「1.14.0 부터다」 꼴을 따른다.

## 의도 메모

- 막는 글의 끝맺음(「그림을 만들지 않았다. 사용자에게 어느 사진을 왜 쓸 수 없는지 알린다.」)은 그대로 둔다(스펙 DECISION32).
- 결과 hook 이 돌지 못해 기억한 링크가 남아도 사본과 같은 상한(`MAX_CALLS`)으로 오래된 것부터 잊는다.

## 작업 항목

### 1. `image_source.py`

- `LINK_PREFIX = "link:"` 를 더하고 `_is_reference` 가 받게 한다. 같은 가리킴은 한 번만 받는 규칙, 16장 상한은 그대로다.
- `NOT_REFERENCE` 를 「바탕과 참고 사진에는 attachment:<첨부 번호>, generated:<그림 제목>, link:<사용자가 보낸 주소> 만 쓴다. 파일 경로와 맨 주소는 쓰지 않는다」 뜻으로, `UNKNOWN` 을 「이 대화에 보낸 사진, 링크나 이 대화에서 만든 그림이 아니다」 뜻으로 고친다.
- `_fetch` 가 `IMAGE_SOURCE_LINK_BLOCKED`(열 수 없는 주소다), `IMAGE_SOURCE_LINK_NO_IMAGE`(이 링크에서 쓸 수 있는 사진을 찾지 못했다. 사진을 직접 올려 달라고 한다), `IMAGE_SOURCE_LINK_TOO_LARGE`(사진이 너무 크다), `IMAGE_SOURCE_LINK_FAILED`(링크를 열지 못했다. 쇼핑몰이 막았을 수 있으니 사진을 직접 올려 달라고 한다)를 까닭 글로 바꾼다. 200 이면 `X-Image-Source-Link` 머리 값을 함께 돌려준다.
- `prepare` 가 `link:` 가리킴의 머리 값을 쓴 순서대로 모아 사본과 같은 키로 기억한다. `links_for(session_id, tool_call_id)` 를 더하고 `release` 가 함께 지운다.

### 2. `generated_image.py`

- 결과 hook 은 `hermes/plugins/fos-ctx/hooks.py` 의 `transform_tool_result` 다. 지금 `release_sources` 를 먼저 부르고 `publish_image` 를 부른다. `release_sources` 앞에서 `links_for` 로 링크를 읽어 두고 `publish_image(args, result, session_id, tool_call_id, source_links=links)` 로 넘긴다.
- `publish` 가 `source_links` 인자를 받아 비지 않으면 `meta["source_links"]` 에 넣는다.
- `sign_generated_image` 에 `source_links=None` 인자를 더해 비지 않으면 메시지 끝에 주소를 `\n` 으로 이은 글의 SHA-256 줄을 더한다.

### 3. README 와 버전

`hermes/plugins/fos-ctx/README.md` 의 「그림을 고칠 사진을 받는다」 에 `link:` 가리킴, 새 오류 코드와 글, `X-Image-Source-Link`, 올리기의 `source_links` 와 서명 줄을 적는다. 플러그인 버전을 한 단계 올린다.

### 4. 테스트

- `hermes/tests/test_image_source.py` 수정: `link:` 가 받아들여지고 첨부와 섞어 쓰며, 네 오류 코드마다 까닭 글이 나오고, 기억한 링크가 `release` 뒤 사라지는지 본다. 가짜 Control Plane 이 `X-Image-Source-Link` 를 준다.
- `hermes/tests/test_generated_image.py` 수정: `transform_tool_result` 경로로 불러 링크를 기억한 호출의 올리기 meta 에 `source_links` 가 있고 서명이 phase 03 의 Java 테스트와 같은 고정 입력에서 같은 값이다. 링크 없는 올리기는 지금 서명 그대로다.
- `hermes/tests/test_image_source.py` 에 첨부는 받고 링크 하나가 실패하는 호출이 막히고, 받은 첨부 사본을 지우고, 링크를 기억하지 않는지 더한다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_*image*.py'
python3 -m unittest discover -s hermes/tests
pnpm --dir web test:browser generated-image.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/image_source.py` | 수정 |
| `hermes/plugins/fos-ctx/generated_image.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/plugins/fos-ctx/plugin.yaml` | 수정 |
| `hermes/tests/test_image_source.py` | 수정 |
| `hermes/tests/test_generated_image.py` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
