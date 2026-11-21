## ADR-20261120 / image-source: 그림을 고칠 바탕 사진은 모델이 가리키고 fos-ctx 가 Control Plane 에서 받아 넘긴다

- **status**: `accepted`
- Date: 2026-11-20
- **decision-type**: `architecture`
- **areas**: `security`
- **현재 규칙의 원본**: 설계 [`docs/features/generated-image/design.md`](../features/generated-image/design.md) 의 「PR3」, 스펙 [`spec.md`](../features/generated-image/spec.md) 의 REQ15~REQ17(사용자 확인 2026-11-19), [`fos-ctx` README](../../hermes/plugins/fos-ctx/README.md) 의 「그림을 고칠 사진을 받는다」
- [ADR-091](ADR-091-사진-첨부는-사용자별로-저장하고-실행-공간에는-그-사용자만-붙인다.md) 의 사진 권한을 넓히지 않는다. [ADR-20261119 / generated-image](ADR-20261119-generated-image.md) 의 hook 에 받기를 더한다

### 결정

**모델은 `image_generate` 의 `image_url` 과 `reference_image_urls` 에 `attachment:<첨부 번호>` 나 `generated:<제목>` 만 적는다. fos-ctx 의 `pre_tool_call` 이 하나씩 Control Plane 의 `/internal/hermes/image-source` 에서 받아 그 profile 의 media cache 에 사본으로 쓰고 인자를 사본 경로로 바꾼다.**

| 항목 | 정한 것 |
| --- | --- |
| 누구의 사진인가 | 요청 본문이 정하지 못한다. Control Plane 이 서명한 호출 맥락의 origin 실행에서 사용자와 대화를 읽고 그 안에서만 찾는다 |
| 쓸 수 있는 것 | 요청자가 그 대화에 보낸 사진과 그 대화의 그림 행. 원본 조회 도구와 같은 판정이다 |
| 가리킴이 아닌 값 | 경로, 주소, data URL 이 하나라도 있으면 fos-ctx 가 막는다 |
| 하나라도 못 받으면 | 그림을 만들지 않고 가리킴마다 까닭을 모델에게 준다(스펙 DECISION32) |
| 넘기는 모양 | media cache 의 파일 경로. 결과 hook 이 지운다 |
| 옛 Control Plane | 가리킴이 든 호출을 막는다. 경로를 받던 동작으로 돌아가지 않는다 |

### 맥락

Hermes 의 `image_generate` 는 바탕과 참고 사진을 주소, data URL, 파일 경로로 받는다. 실행 공간이 있는 profile 은 파일 경로를 실행 공간 안에서 읽는데, 실행 공간에는 그 사용자의 모든 대화의 사진이 붙어 있다(ADR-091).
그래서 경로를 그대로 두면 모델이 다른 대화의 사진을 쓸 수 있다. 만든 그림은 Control Plane 의 대화 결과물 폴더에 있어 Hermes 가 읽지 못한다.

### 대안 기각

- 실행 공간의 경로를 그대로 받고 대화 번호로 다른 대화의 경로를 뺀다: 경로에서 대화를 알아내려면 fos-ctx 가 첨부 저장 구조를 알아야 하고, 만든 그림은 여전히 읽지 못한다.
- Control Plane 이 data URL 을 돌려주고 fos-ctx 가 인자에 넣는다: 고친 인자는 Hermes 의 도구 사건과 결과 hook 에 그대로 간다. 수 MB 의 base64 가 사건으로 흐르고 JSON 으로 두 배의 메모리를 쓴다.
- 실행 입력에 바탕 사진을 미리 넣어 둔다: 모델이 어느 사진을 고를지 turn 전에 알 수 없다. 지난 사진과 그림을 매 turn 넣는 비용이 크다.
- 사진을 받는 전용 도구를 plugin 이 등록한다: Hermes 의 이미지 생성 내부 함수를 직접 불러야 하고 내장 도구와 둘이 함께 보인다. [ADR-20261119 / generated-image](ADR-20261119-generated-image.md) 가 제목 도구를 기각한 까닭과 같다.

### 결과

- 얻는 것: 사진 권한 확인이 원본 조회 도구와 같은 한 곳에 있다. 만든 그림도 같은 길로 바탕이 된다. Hermes core 를 고치지 않는다.
- 감당할 것: Hermes 가 입력 경로를 media cache 안에서 호스트로 읽는 동작과 이미지 서비스의 16장 상한에 기댄다. 계약 검사가 Hermes 를 올릴 때 다시 본다. 사본은 결과 hook 이 돌지 못하면 다음 받기까지 media cache 에 남는다.
- 확인 방법: Control Plane 의 받기 통합 테스트와 fos-ctx 의 단위 테스트. 실제 이미지 서비스로 사진 한 장을 고치는 왕복은 배포 뒤 운영에서 확인한다.
