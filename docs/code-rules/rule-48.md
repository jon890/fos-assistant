# rule-48

Memory 권한은 Control Plane이 정하고 비밀 원문은 DB에 두지 않는다.

검사: [기억 비밀값 회귀](../../test/unit/memory-secrecy.test.ts), [리뷰](../../.github/workflows/code-review-prompt.txt)

## 까닭

기억의 내용과 사용자의 비밀을 권한 밖 실행에 싣지 않게 한다.

## 예외와 검토 범위

Hermes 내장 memory 도구는 주지 않는다.
제목만 주입한 항목은 Control Plane이 고르는 MCP 응답으로 읽는다.
실행 밖 서비스에 Memory 문서를 읽는 경로는 두지 않는다.
key 원문을 DB에 넣는 것과 사용자 본문의 암호화 저장은 다르다.

## 이 저장소의 사례

[memory-secrecy.test.ts](../../test/unit/memory-secrecy.test.ts)에서 확인한다.
memory-secrecy 테스트가 비밀 본문이 응답에 남는 경로를 확인한다.
