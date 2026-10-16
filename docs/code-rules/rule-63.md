# rule-63

Hermes core를 고치기 전에 plugin·profile·API 경계를 쓴다.

검사: [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

Hermes를 올릴 때 자체 core 패치를 계속 유지하는 부담을 만들지 않는다.

## 예외와 검토 범위

plugin·profile·API server로 풀 수 없는 경우 ADR-001의 검토 순서를 먼저 따른다.

## 이 저장소의 사례

[ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md](../../docs/adr/ADR-001-hermes를-런타임으로-두고-core를-고치지-않는다.md)에서 확인한다.
ADR-001은 자체 core 패치 전에 검토할 확장 경계를 정한다.
