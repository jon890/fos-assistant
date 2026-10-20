# Phase 02. 정답 글자를 심은 합성 사진 12장과 시나리오 데이터를 만든다

**Execution profile**: standard

## 목표

블로그 사진 평가가 쓸 합성 사진 12장, 사진마다의 정답 글자, 네 시나리오를 저장소에 둔다.
정답 글자는 그 사진에만 있고 지시문에는 없어서, 미리보기 본문에 그 글자가 있으면 모델이 그 사진을 읽었다고 볼 수 있다.

**범위 외**: 평가 스크립트와 판정 규칙(phase 03), Control Plane 의 단계 사건(phase 01).

## 컨텍스트

- 시나리오와 판정의 뜻은 `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」 가 갖는다. 시나리오 id 는 `past-only`, `same-message`, `mixed`, `many-past` 넷이고 그 절의 「시나리오」 표와 같아야 한다
- 이 저장소는 공개 저장소다. 실제 가게 이름, 주소, 사람, 실제 사진을 쓰지 않는다. 정답 글자는 지어낸 이름이다
- 기존 합성 사진 생성기 `test/support/MediaFixtureGenerator.java` 는 OS 글꼴 없이 무늬로 ID 를 그린다. 모델이 읽을 한글 글자를 그리지 못하므로 쓰지 않는다
- `test/` 의 TypeScript 는 Node 22.18 이상의 타입 제거 실행으로 돈다. 빌드 단계가 없다. 단위 테스트는 `node --test` 와 `node:assert/strict` 를 쓴다. 기존 예는 `test/unit/media-pipeline-log.test.ts` 다

**근거 문서**: `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」, `docs/adr/ADR-20261020-blog-photo-eval.md` 의 결정 2

## 의도 메모

- 사진은 실행할 때 만들지 않고 만든 결과를 커밋한다. 평가가 도는 머신에 한글 글꼴이 있는지에 기대지 않고, 매번 같은 바이트로 평가하려는 것이다
- 생성 스크립트는 개발 머신에서 한 번 돌리는 도구다. Pillow 와 한글 글꼴은 저장소의 의존이 아니다. 글꼴 경로는 인자로 받는다
- 정답 글자는 사진마다 하나다. 허용 표기는 띄어쓰기를 지운 뒤 비교하므로 띄어쓰기 변형은 따로 적지 않는다. 숫자 표기처럼 글자가 달라지는 변형만 적는다
- 지시문은 「주소와 지도는 넣지 말고 사진에 보이는 것만으로 써 줘」 와 카테고리 자리표시자 `{category}` 를 담는다. 스킬이 장소 이름이나 주소, 카테고리를 되물을 까닭을 없애려는 것이다

## Blocked 조건

- Pillow 를 가져오지 못하거나 한글 글꼴 파일이 없다 → `PHASE_BLOCKED: 합성 사진 생성에 Pillow 와 한글 글꼴이 필요하다` 출력 후 종료

## 작업 항목

### 1. `test/model-eval/blog/photos/photos.json`

`{"version": 1, "photos": [...]}` 이다. 항목은 `{"id", "file", "scene", "answer", "accept"}` 다.

- `id` 는 `p01` 부터 `p12`, `file` 은 `p01.jpg` 부터 `p12.jpg` 다
- `scene` 은 생성 스크립트가 그릴 장면의 짧은 한국어 설명이고, `answer` 는 사진에 크게 쓸 정답 글자, `accept` 는 판정이 받는 표기 목록이다. `accept` 의 첫 값은 `answer` 다

| id | scene | answer | accept 에 더할 표기 |
| --- | --- | --- | --- |
| `p01` | 국숫집 간판 | 가상국수 | |
| `p02` | 벽에 붙은 메뉴판 | 들기름막국수 | |
| `p03` | 출입문의 영업 안내문 | 매주 화요일 휴무 | `화요일` |
| `p04` | 접시에 꽂힌 이름표 | 수육 반접시 | `수육` |
| `p05` | 산책로 표지판 | 솔바람길 1.2km | `솔바람길` |
| `p06` | 계산 영수증 | 합계 27,000원 | `27,000`, `27000`, `2만 7천` |
| `p07` | 카페 간판 | 구름다방 | |
| `p08` | 칠판 메뉴 | 흑임자라떼 | |
| `p09` | 주차 안내판 | 주차 2시간 무료 | `2시간 무료`, `두 시간 무료` |
| `p10` | 기념품 꼬리표 | 솔방울 열쇠고리 | `솔방울 키링` |
| `p11` | 버스 정류장 이름판 | 가상마을 입구 | `가상마을` |
| `p12` | 전시 포스터 | 가을 들꽃전 | `들꽃전` |

### 2. `test/model-eval/blog/scenarios.json`

`{"version": 1, "scenarios": [...]}` 이다. 항목은 `{"id", "messages": [{"text", "photos": [사진 id...]}]}` 다. 마지막 메시지가 블로그를 부탁하는 메시지다.

| id | messages |
| --- | --- |
| `past-only` | 1. 「주말에 다녀온 곳 사진이야.」, `p01`~`p06` 2. 「앞에 올린 사진 여섯 장으로 네이버 블로그 글 초안을 써 줘. 주소와 지도는 넣지 말고 사진에 보이는 것만으로 써 줘. 카테고리는 {category}. 미리보기까지만 보여 줘.」, 사진 없음 |
| `same-message` | 1. 「이 사진 여섯 장으로 네이버 블로그 글 초안을 써 줘. (나머지는 `past-only` 의 2 와 같은 문장)」, `p01`~`p06` |
| `mixed` | 1. 「첫 번째 사진들이야.」, `p01`~`p03` 2. 「앞에 올린 세 장과 지금 올린 세 장, 모두 여섯 장으로 네이버 블로그 글 초안을 써 줘. (나머지는 같은 문장)」, `p04`~`p06` |
| `many-past` | 1. 「첫날 사진이야.」, `p01`~`p06` 2. 「둘째 날 사진이야.」, `p07`~`p12` 3. 「앞에 올린 사진 열두 장으로 네이버 블로그 글 초안을 써 줘. (나머지는 같은 문장)」, 사진 없음 |

### 3. `test/model-eval/blog/make_photos.py`

- 인자는 `--font <한글 글꼴 파일>` 과 `--out <디렉터리>` 다. `photos.json` 은 스크립트 옆 `photos/photos.json` 에서 읽는다
- 사진마다 1280×960 RGB 이미지를 만든다. 사진 번호로 정한 배경색과 장면을 흉내 낸 단순한 도형(간판 판, 종이, 표지판 기둥) 위에 `answer` 를 글꼴 크기 96 이상으로 쓴다. 다른 사진의 `answer` 나 `accept` 표기는 쓰지 않는다
- 무작위 값을 쓰지 않는다. 같은 글꼴로 다시 돌리면 같은 바이트가 나와야 한다. EXIF 없이 JPEG 품질 90 으로 저장한다
- 스크립트 머리 주석에 쓰는 법, 글꼴을 인자로 받는 까닭, 만든 결과를 커밋한다는 것을 적는다
- 만든 `p01.jpg` 부터 `p12.jpg` 를 `test/model-eval/blog/photos/` 에 커밋한다. 개발 머신에서 돌린 글꼴 경로는 커밋 메시지에도 적지 않는다

### 4. 이 phase 를 검증하는 `test/unit/blog-photo-eval-data.test.ts`

- `photos.json` 의 사진이 12장이고 `id` 와 `file` 이 겹치지 않으며, 파일마다 있고 첫 두 바이트가 `FF D8` 이다
- `accept` 의 첫 값이 `answer` 이고, 띄어쓰기를 지운 `accept` 표기가 다른 사진의 표기와 같거나 한쪽이 다른 쪽의 부분 문자열이 아니다(판정이 포함 여부로 보므로 부분 문자열도 거짓 `PASS` 를 낸다)
- `scenarios.json` 의 시나리오 id 가 위 넷과 정확히 같다. 메시지가 고른 사진 id 가 모두 있고, 한 시나리오 안에서 같은 사진을 두 번 쓰지 않는다
- 마지막 메시지에만 `{category}` 가 정확히 한 번 있다. `past-only` 와 `many-past` 의 마지막 메시지에는 사진이 없다
- 어느 메시지의 글에도 띄어쓰기를 지운 `accept` 표기가 들어 있지 않다. 들어 있으면 정답이 지시문에서 새어 나간 것이다
- 정답이 새는 실패 입력 하나(지시문에 `가상국수` 를 넣은 사본)를 검사 함수에 넣어 거절하는지 본다. 그래서 새는 검사를 함수로 나눠 테스트 안에서 부른다

## 검증

```bash
python3 -c "import PIL"
node --test test/unit/blog-photo-eval-data.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/checks/check-file-length.mjs
```

모두 종료 코드 0 이다. 사진을 열어 정답 글자가 읽히는지 눈으로 한 번 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/model-eval/blog/photos/photos.json` | 신규 |
| `test/model-eval/blog/photos/p01.jpg` | 신규 |
| `test/model-eval/blog/photos/p02.jpg` | 신규 |
| `test/model-eval/blog/photos/p03.jpg` | 신규 |
| `test/model-eval/blog/photos/p04.jpg` | 신규 |
| `test/model-eval/blog/photos/p05.jpg` | 신규 |
| `test/model-eval/blog/photos/p06.jpg` | 신규 |
| `test/model-eval/blog/photos/p07.jpg` | 신규 |
| `test/model-eval/blog/photos/p08.jpg` | 신규 |
| `test/model-eval/blog/photos/p09.jpg` | 신규 |
| `test/model-eval/blog/photos/p10.jpg` | 신규 |
| `test/model-eval/blog/photos/p11.jpg` | 신규 |
| `test/model-eval/blog/photos/p12.jpg` | 신규 |
| `test/model-eval/blog/scenarios.json` | 신규 |
| `test/model-eval/blog/make_photos.py` | 신규 |
| `test/unit/blog-photo-eval-data.test.ts` | 신규 |
