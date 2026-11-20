# 비서가 찾은 주소 받기 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ2 | 알린 주소만 받는다. 가짜 받기 객체, 서명한 호출 맥락 | `cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.FoundLinkEndpointTest'` | 알린 주소는 200, 알리지 않은 주소와 글자를 바꾼 주소는 422 `IMAGE_SOURCE_UNKNOWN` 이고 바깥 요청이 없다 | 로컬 H2 | worker | 없음 | PR 전 |
| REQ2 | 다른 대화와 다른 사용자의 주소를 받지 않는다. 사용자 둘, 대화 둘 | 같은 테스트 | 다른 대화와 다른 사용자의 실행이 알린 주소는 422 `IMAGE_SOURCE_UNKNOWN` | 로컬 H2 | worker | 없음 | PR 전 |
| REQ2 | 같은 주소로 보는 범위와 스스로 적어 넣은 주소. 없음 | `cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.conversation.application.FoundLinksTest'` | 엔티티를 푼 꼴, `//` 꼴, 상대 주소를 푼 꼴이 통과하고 기준 주소와 같은 후보는 통과하지 않는다 | 로컬 H2 | worker | 없음 | PR 전 |
| REQ2 | fos-ctx 가 결과에서 주소를 뽑는다. 결과 표본 | `python3 -m unittest discover -s hermes/tests -p 'test_found_links*.py'` | 검색 결과의 주소와 읽은 본문의 주소가 뽑히고 읽은 페이지 자신의 주소와 오류 글의 주소는 뽑히지 않는다. 알리기가 실패해도 결과가 그대로다 | 로컬 | worker | 없음 | PR 전 |
| REQ2 | MySQL 에서 해시 이어 붙이기와 정리 뒤 칸 비우기. 없음 | Draft CI 의 `backend-mysql` job(`RepositoryQueryMysqlTest`, `PurgeCoverageMysqlTest`) | 통과 | CI | worker | push | PR 뒤 |
| REQ3 | 찾은 주소도 같은 검사로 막힌다. 가짜 받기 객체가 거절한다 | `cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.FoundLinkEndpointTest'` | 막힌 주소는 `IMAGE_SOURCE_LINK_BLOCKED`, 사진 아님은 `IMAGE_SOURCE_LINK_NO_IMAGE`, 20MB 초과는 `IMAGE_SOURCE_LINK_TOO_LARGE` | 로컬 H2 | worker | 없음 | PR 전 |
| REQ3 | 받기 객체의 검사 자체. 없음 | `cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.artifact.infra.LinkImageFetcherTest'` | 통과(바뀌지 않았다) | 로컬 H2 | worker | 없음 | PR 전 |
| REQ8 | 저장된 꼴로 다시 가리킨다. 찾은 주소를 한 번 받았다 | `cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.FoundLinkEndpointTest'` | 받기 경로가 돌려준 주소(`https://`, 퍼센트 인코딩)를 `link:` 로 다시 가리키면 200 | 로컬 H2 | worker | 없음 | PR 전 |
| REQ9 | 운영에서 찾아 그리기. fos-ctx 와 Control Plane 을 배포했다 | 운영 저장소의 배포 확인 절차로 스펙 REQ9 의 부탁을 링크 없이 보낸다 | 답에 그림이 보이고 「그림 지시문 보기」 의 참고한 링크에 비서가 찾은 주소가 있으며 양말 모양이 그 사진과 맞는다 | 운영 | 코디네이터 | 배포 | PR 뒤 |
