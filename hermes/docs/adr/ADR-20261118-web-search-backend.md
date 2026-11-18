## ADR-20261118 / web-search-backend: 웹 검색 서비스는 값을 정하지 않고 Hermes 자동 선택을 유지하며 OpenAI 내장 검색은 쓰지 않는다

- **status**: `accepted`
- Date: 2026-11-18
- **decision-type**: `architecture`
- **areas**: `operations`
- **현재 규칙의 원본**: 스펙 [`docs/features/answer-quality/spec.md`](../../../docs/features/answer-quality/spec.md)(DECISION2, DECISION10), 기능 파일 [`docs/features/answer-quality.md`](../../../docs/features/answer-quality.md) 의 「검색 서비스」

### 결정

**profile 의 `web.search_backend` 를 정하지 않고 Hermes 가 스스로 고르게 둔다(지금은 key 없는 Exa).**

### 맥락

스펙 DECISION2 는 지금 서비스와 OpenAI 내장 검색을 같은 평가(17개 검색 문항)로 돌려 앞서는 쪽을 쓰고 비슷하면 지금 서비스를 쓰기로 했다. 운영에서 기본 에이전트와 대화 기본 모델 칸으로 측정했다.

| 이름표 | Hermes | 검색 서비스 | 출처 문항(17) | 열린 링크 |
| --- | --- | --- | --- | --- |
| pr1-v0232-free | v0.23.2 | 자동 선택 | 13 | 29/31 |
| pr1-v0232-native | v0.23.2 | `openai-native` | 0 | 0/0 |

내장 검색에서는 평가 세트 20문항 모두(검색 문항 17 포함) 답에 출처 링크가 없었고 실행 사건에도 검색이 남지 않았다. 링크가 없는 까닭(서버 검색이 돌지 않았는지, 인용이 annotation 으로만 오고 본문에 쓰이지 않았는지)은 확인하지 않았다.

### 대안 기각

- **OpenAI 내장 검색**: 출처 0 < 13 이라 규칙대로 기각했다. 검색어와 횟수도 실행 사건에 남지 않는다. 운영 설정은 바꾸기 전 상태로 되돌렸다.
- **유료 검색 API**: 스펙 범위 밖이다.

### 결과

- 얻는 것: 검색어와 횟수가 실행 사건에 남고, 출처 문항이 기준 쪽으로 가장 가깝다.
- 감당할 것: 출처 문항이 기준(15)에 2문항 모자라 지침을 고쳐야 한다. 내장 검색이 안 붙은 까닭을 모르므로 Hermes 버전이 바뀌면 다시 잴 수 있다.
- **확인 방법**: `test/model-eval/answer/` 평가를 지침이나 검색 서비스를 바꿀 때 다시 돌린다.
