## ADR-20261119 / generated-image: 비서가 만든 그림은 fos-ctx 가 Control Plane 에 올리고 대화의 결과물로 묶는다

- **status**: `accepted`
- Date: 2026-11-19
- **decision-type**: `architecture`
- **areas**: `security, data`
- **현재 규칙의 원본**: 설계 [`docs/features/generated-image/design.md`](../features/generated-image/design.md), 스펙 [`spec.md`](../features/generated-image/spec.md) 의 REQ1~REQ10(사용자 확인 2026-11-19), [`fos-ctx` README](../../hermes/plugins/fos-ctx/README.md) 의 「만든 그림을 올린다」
- [ADR-027](ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 의 「답에 묶는 결과물」 에 그림을 더한다. 저장된 답 글을 고치지 않는 원칙은 그대로다

### 결정

**`image_generate` 가 만든 그림은 fos-ctx 의 `transform_tool_result` hook 이 읽어 Control Plane 의 내부 경로로 올린다. Control Plane 이 origin 실행에서 요청자와 대화를 정해 대화의 결과물 폴더에 쓰고 답에 묶는다.**

| 항목 | 정한 것 |
| --- | --- |
| 올리는 곳 | `/internal/hermes/generated-image`. 판정 경로와 같은 profile 토큰과 서명 key. 서명은 호출 세 칸과 그림, 제목, 지시문의 SHA-256 을 묶는다 |
| 누구의 그림인가 | 요청 본문이 정하지 못한다. Control Plane 이 서명한 session 의 origin 실행에서 사용자와 대화를 읽는다 |
| 모델이 받는 결과 | 경로가 없는 글로 바꾼다. 답에 경로나 `MEDIA:` 가 나올 까닭을 없앤다 |
| 제목 | `image_generate` 에 제목 칸이 없어, 모델이 지시문 첫 줄에 적은 `제목:` 줄을 `pre_tool_call` 이 떼어 둔다 |
| 답에 남은 경로 | 저장된 답은 고치지 않고, 메시지 응답과 화면이 내보낼 때 `MEDIA:` 태그와 Hermes 이미지 캐시 경로를 뺀다(사용자 결정 2026-11-19). 홈서버의 디렉터리 구조가 화면에 보이지 않게 한다 |
| 저장 | 대화 결과물 폴더의 파일과 `chat_artifact` 행. 지시문은 그 행의 암호화한 칸이다. 새 표는 없다 |

### 맥락

Hermes 는 그림을 그 profile 의 캐시 폴더에 쓰고 모델에게 호스트 경로를 돌려준다. `/v1/runs` 는 `MEDIA:` 를 그림으로 바꾸지 않아 대화에는 경로 글자만 보였다(2026-11-19 운영 확인).
Control Plane 은 Hermes 의 캐시 폴더를 읽지 못한다. 모델은 `artifact_write` 로 옮기려 했지만 그 도구는 그림을 HTTPS 주소로만 받는다.

### 대안 기각

- `artifact_write` 가 그림 바이트를 받는다: 모델이 파일을 base64 로 읽어 인자로 넘겨야 한다. 수 MB 의 글이 모델 문맥을 지나 비용이 크고, 모델이 옮기기를 빠뜨리면 그림이 보이지 않는다.
- Hermes 가 대화 결과물 폴더에 직접 복사한다: plugin 이 대화 번호와 폴더 경로를 알아야 하고, 누구의 대화인지 Hermes 쪽에서 정하게 된다. 실행할 대상을 Control Plane 이 정한다는 경계와 어긋난다.
- 답 글의 `MEDIA:` 를 Control Plane 이 읽어 파일을 가져온다: Control Plane 이 Hermes 의 캐시 폴더를 읽는 길이 필요하고, 모델이 적은 경로를 믿어야 한다. ADR-027 이 답에 적힌 경로를 읽지 않기로 한 까닭과 같다.
- OpenAI 호환 경로로 바꾼다: 그 경로는 `MEDIA:` 를 data URL 로 바꾸지만 실행 사건과 중지, 승인 계약이 모두 `/v1/runs` 위에 있다.
- 제목을 받는 전용 도구를 plugin 이 등록한다: Hermes 의 이미지 생성 내부 함수를 plugin 이 직접 불러야 한다. 새 내부 지점에 기대게 되고 내장 도구와 둘이 함께 보인다.

### 결과

- 얻는 것: 그림이 요청자의 대화 결과물이 되어 기존 권한 확인, 보관 기간 정리, 자동 보관을 그대로 쓴다. Hermes core 와 새 내부 지점에 기대지 않는다.
- 감당할 것: 결과 hook 은 실패해도 닫히지 않는다. Hermes 가 hook 을 건너뛰면 모델이 경로를 받아 답에 적을 수 있고, 그 글자는 내보낼 때의 가리기가 뺀다. 가리기는 정해 둔 모양의 경로만 잡는다. 제목 줄은 모델이 지침을 따를 때만 온다. 따르지 않으면 날짜와 시각이 제목이다.
- 확인 방법: Control Plane 의 올리기 통합 테스트와 fos-ctx 의 단위 테스트가 같은 서명 확인 값을 쓴다. 실제 이미지 서비스 왕복은 배포 뒤 운영에서 확인한다.
