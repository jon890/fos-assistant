# 비서가 만든 이미지

사용자가 대화로 부탁한 그림을 비서가 만들어 답 안에 보이고 「보관한 결과물」 에 남기는 기능이다.

아직 구현 전이다. 요구와 결정은 스펙 [`generated-image/spec.md`](generated-image/spec.md) 가 갖는다.
구현이 들어오면 이 파일에 흐름과 화면, Control Plane 과 Hermes 사이의 계약을 적는다.

이 기능이 기대는 기존 규칙은 아래 문서가 갖는다.

- 답에 묶는 결과물과 대화별 결과물 폴더: [`attachment.md`](attachment.md) 의 「결과물 파일」
- 「보관한 결과물」 과 자동 보관: [`workspace.md`](workspace.md) 의 「보관한 결과물」
- 「그림 만들기」 도구의 등급과 켜는 권한: [`agent-skill.md`](agent-skill.md)
