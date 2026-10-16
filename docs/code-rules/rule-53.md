# rule-53

기능·ADR·구현 계획은 지정한 자리에 두고 끝난 계획은 지운다.

검사: [문서 배치](../../test/unit/doc-files.test.ts), [ADR 목록](../../test/unit/adr-index.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

문서의 역할과 수명을 정해 끝난 계획을 현재 설계로 읽지 않게 한다.

## 예외와 검토 범위

기능은 루트 features, ADR은 소유 모듈에 둔다.
끝난 계획은 구현 PR에서 지우고 장기 근거는 docs나 ADR에 남긴다.
코드와 문서는 계획 번호를 참조하지 않는다.
규칙 문서는 code-rules만 좁게 허용한다.

## 이 저장소의 사례

[doc-files.test.ts](../../test/unit/doc-files.test.ts)에서 확인한다.
doc-files 테스트는 정해진 문서 경로와 좁은 예외만 허용한다.
