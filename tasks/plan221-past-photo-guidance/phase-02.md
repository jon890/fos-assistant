# Phase 02. 블로그 스킬이 글을 쓰기 전에 쓸 사진을 모두 조회하게 한다

**Execution profile**: fast

## 목표

네이버 블로그 스킬의 「새 글 쓰기」 1단계는 「사진을 모두 보고」 한 마디뿐이다. 앞 메시지에 올린 사진을 어떻게 보는지, 사진이 많을 때 어떻게 하는지를 절차로 적는다.
「사실은 사용자에게 묻는다」 는 규칙이 사진을 보기 전에 되묻는 쪽으로 읽히지 않게 고친다.

**범위 외**: 실행 입력의 안내 문구(phase 01). 커넥터 도구와 manifest.

## 컨텍스트

- 스킬은 `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` 다. 「지킬 것」 의 셋째 항목이 「사진에 없고 사용자가 말하지 않은 사실은 쓰지 않는다. 장소 이름, 주소, 가격처럼 글에 꼭 필요한 사실은 사용자에게 묻는다」 이고, 「새 글 쓰기」 의 1 이 「사진을 모두 보고, 위 「카테고리 고르기」 로 카테고리를 정한다」 다
- 같은 파일의 「사진 보기」 절이 `attachment_inspect` 의 `region`, `overview` 쓰는 법과 조회 실패 때의 답을 이미 갖는다. 그 내용을 다시 쓰지 않고 그 절을 가리킨다
- 한 메시지에 올릴 수 있는 사진은 30장이다(`docs/features/attachment.md` 의 「사진 첨부」). 원본 조회 한도는 사진마다 3회, 실행마다 90회다(같은 문서의 「원본과 영역을 자동 조회할 때」)
- 스킬 파일의 앞머리와 비밀값 칸은 `hermes/tests/test_connectors_contract.py` 가 검사한다. 앞머리는 바꾸지 않는다

**근거 문서**: `docs/features/attachment.md` 의 「실행 입력에 사진을 싣는 법」, `docs/features/naver-blog-connector.md` 의 「블로그 사진 평가」

## 의도 메모

- 2026-10-20 운영 기록에서 지난 사진 67장이 있는 대화의 블로그 요청 실행 15개가 `attachment_inspect` 를 한 번만 불렀고, 에이전트는 사진 설명을 부탁했다
- 쓸 사진을 사용자가 정하지 않았으면 보관 중인 사진을 모두 쓴다. 30장을 넘으면 실행 하나의 조회 한도 안에서 모두 보기 어려우므로 몇 번째부터 몇 번째까지 쓸지 먼저 묻는다. 묻는 것은 사진의 범위이고 사진의 내용이 아니다
- 사용자에게 재업로드나 분할 전송을 요구하지 않는다는 기존 규칙은 그대로 둔다

## 작업 항목

### 1. `SKILL.md` 의 「지킬 것」 셋째 항목

`hermes/connectors/naver-blog/skills/naver-blog/SKILL.md`

다음 문장으로 바꾼다.

> **사진에 없고 사용자가 말하지 않은 사실은 쓰지 않는다.** 장소 이름, 주소, 가격처럼 글에 꼭 필요한 사실은 쓸 사진을 모두 본 뒤에도 알 수 없을 때만 사용자에게 묻는다. 사진에 무엇이 있는지는 묻지 않는다

### 2. `SKILL.md` 의 「새 글 쓰기」 1

다음 하위 절차로 바꾼다. 2 부터의 번호와 문장은 그대로 둔다.

1. 글에 쓸 사진을 정하고 모두 본다
   - 사용자가 쓸 사진을 말하지 않았으면 대화 입력의 사진 참조 목록에서 보관 중인 사진을 모두 쓴다. 그 수가 30장을 넘으면 몇 번째부터 몇 번째 사진까지 쓸지 먼저 묻는다
   - 이번 메시지에 이미지로 실린 사진은 그대로 본다. 실리지 않은 사진과 앞 메시지의 사진은 사진 참조의 `attachment_id` 로 `attachment_inspect` 를 한 장씩 불러 본다. 쓰는 법과 실패 때의 답은 위 「사진 보기」 를 따른다
   - 쓸 사진을 모두 보기 전에는 초안을 만들지 않고, 사용자에게 사진의 내용을 설명해 달라고 하지 않는다
   - 그다음 위 「카테고리 고르기」 로 카테고리를 정한다

### 3. 이 phase 를 검증하는 검사

스킬 문장만 바꾸므로 새 테스트 파일을 만들지 않는다. 기존 계약 검사로 앞머리와 비밀값 칸이 그대로인지 보고, 바뀐 문장이 들어갔는지 grep 으로 본다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connectors_contract.py' -k skills -k front_matter
grep -q "쓸 사진을 모두 본 뒤에도 알 수 없을 때만" hermes/connectors/naver-blog/skills/naver-blog/SKILL.md
grep -q "쓸 사진을 모두 보기 전에는 초안을 만들지 않고" hermes/connectors/naver-blog/skills/naver-blog/SKILL.md
! grep -q "사진을 모두 보고, 위" hermes/connectors/naver-blog/skills/naver-blog/SKILL.md
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/skills/naver-blog/SKILL.md` | 수정 |
