# rule-44

파일 길이 상한과 기존 위반 기준을 지킨다.

검사: [파일 길이](../../scripts/checks/check-file-length.mjs)

## 까닭

파일이 계속 커져 책임을 찾기 어려워지는 것을 막는다.

## 예외와 검토 범위

언어별 범위와 상한 및 문서 경고는 검사 script가 갖는다.
기존 초과 파일은 기준 목록을 줄여 간다.

## 이 저장소의 사례

[file-length-baseline.json](../../scripts/checks/file-length-baseline.json)에서 확인한다.
file-length-baseline.json은 기존 초과 파일과 제외 사유를 보관한다.

책임과 실행 절차는 [code-architecture.md](../../backend/docs/code-architecture.md)에서 이어 읽는다.
