# Phase 01. 그림 규칙에 인물 줄, 이어지는 장면, 비율과 구도를 더한다

**Execution profile**: standard

## 목표

모델이 매 turn 받는 그림 규칙(`ArtifactService.IMAGE_RULES`)이 사람이 나오는 그림의 인물 수와 사람별 줄, 이어지는 장면의 참고, 기본 비율과 구도를 안내한다.
「이 대화에서 만든 그림」 줄이 그림마다 참고한 링크를 보여 이어지는 장면에서 그 링크를 다시 넣을 수 있다.

**범위 외**: 화면, 새 경로와 칸, fos-ctx 의 동작 변경. 운영의 품질 비교(스펙 DECISION42).

**요구사항**: REQ24, REQ25, REQ26

**근거 문서**: `docs/features/generated-image/spec.md`, `docs/features/generated-image/design.md` 의 「PR6: 그림 지시문 틀」

## 의도 메모

- 규칙은 조건 줄(도구가 있고 그림을 부탁했을 때만) 아래에 둔다. 머리 문장의 줄 수를 함께 고친다
- Hermes 의 `aspect_ratio` 기본값이 `landscape` 라 규칙이 비율을 늘 적게 한다. 값 셋은 계약 테스트로 고정한다(rule-64)
- 그림 제목 줄의 링크는 `GeneratedImageService.sourceLinksOf` 로 푼다. `link:` 상한(2,048자)을 넘는 링크는 적지 않는다
- `ConversationLinks` 는 호스트의 IDN 표기 차이만 더 받는다. 다른 비교 규칙은 바꾸지 않는다

## 작업 항목

1. `IMAGE_RULES` 에 설계의 네 줄을 더하고 지시문 줄에 한국어를 넣는다. `ArtifactTest` 의 기대 글을 고친다
2. `GeneratedImageService` 에 제목과 링크를 함께 돌려주는 조회를 두고 `ArtifactService.imageTitles` 가 링크를 붙인다
3. `ConversationLinks.Target` 이 호스트를 punycode 와 한글 두 표기로 비교한다. `ConversationLinksTest` 에 사례를 더한다
4. `ImageSourceEndpointTest` 에 저장된 꼴의 링크를 다시 받는 사례를 더한다
5. `hermes/tests/hermes_contract.py` 와 `test_generated_image_contract.py` 에 비율 값과 기본값을 더한다. `test_image_source.py` 에 지침대로 쓴 호출 사례를 더한다
6. `docs/features/generated-image.md` 에 지시문 틀을 적는다

## 검증

```bash
# cwd: backend
./gradlew test --tests '*ArtifactTest' --tests '*ConversationLinksTest' --tests '*ImageSourceEndpointTest'
# cwd: hermes
python3 -m unittest discover -s tests -p 'test_image_source.py'
python3 -m unittest discover -s tests -p 'test_generated_image_contract.py'
```
