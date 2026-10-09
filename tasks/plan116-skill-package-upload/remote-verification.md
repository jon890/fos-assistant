# 원격 검증

| 선행 조건 | 실행 위치 | 명령 | 기대값 |
|---|---|---|---|
| 머지와 Control Plane 배포 뒤 | 운영 화면을 쓸 수 있는 브라우저나 API 클라이언트 | 시험용 에이전트에 `SKILL.md` 하나인 zip 을 `POST /api/v1/agents/{code}/skill-packages/preview` 로 보낸 뒤 같은 zip 을 `skill-packages` 로 올린다 | 미리보기가 문제 없이 200, 올리기가 200 이고 스킬 목록에 보인다. 운영 확인 절차는 `fos-home-infra` 가 갖는다 |
