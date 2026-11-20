# 서버 비밀값

관리자가 서버 비밀값을 관리자 화면에서 넣고 바꾸며, Control Plane 이 그 값을 KEK 로 암호화해 데이터베이스에 두는 기능이다.

요구와 결정은 스펙 [`secret-store/spec.md`](secret-store/spec.md) 가 갖는다. 아직 구현하지 않았다.
지금 비밀값을 두는 곳은 [`code-architecture.md`](../code-architecture.md) 의 「비밀값을 두는 곳」 이 갖는다.
