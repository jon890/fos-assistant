# 링크 사진 받기(PR5) 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ20 | 요청자가 대화에 지어낸 상품 페이지 주소를 보낸 메시지, 그 페이지가 `og:image` 로 PNG 를 내는 가짜 DNS 와 가짜 전송 | `ImageSourceEndpointTest` 의 링크 사례로 `link:<주소>` 를 받기 경로에 보낸다 | 200, 본문이 대표 사진 바이트와 같고 `X-Image-Source-Link` 가 정해진 꼴의 https 주소 | 로컬 H2 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ20 | 가짜 Control Plane 이 `link:` 에 사진과 `X-Image-Source-Link` 를 주는 fos-ctx 단위 테스트 | `test_image_source.py` 에서 `image_url` 에 `link:`, `reference_image_urls` 에 `attachment:` 를 섞어 `prepare` 를 부른다 | 두 인자가 사본 경로로 바뀌고 바탕이 링크 사진이다 | 로컬 Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ20 | 그림 규칙 | `ArtifactTest` 의 실행 입력 사례 | 실행 입력에 `link:` 줄이 있고 「인터넷 주소는 쓰지 않는다」 단독 문장이 없다. 받은 사진으로 만든 그림이 답에 보이는 것은 PR1 의 올리기 경로(`GeneratedImageTurnTest`)를 그대로 타므로 기존 테스트가 이어서 증명한다 | 로컬 H2 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ21 | 첨부 하나와 `IMAGE_SOURCE_LINK_FAILED` 를 주는 링크 하나를 가리킨 호출 | `test_image_source.py` 로 `prepare` 를 부른다 | 호출이 막히고 받은 첨부 사본이 지워지며 링크가 기억되지 않는다 | 로컬 Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ21 | 다른 대화와 다른 사용자의 메시지에만 있는 주소, 대화에 없는 주소 | 받기 경로에 그 주소를 `link:` 로 보낸다 | 422 `IMAGE_SOURCE_UNKNOWN`, 가짜 전송이 한 번도 불리지 않는다 | 로컬 H2 Control Plane 통합 테스트 | worker | 없음 | PR 전 |
| REQ21 | http, 내부망과 예약 주소로 풀리는 이름, 넘기기 끝의 내부망 주소, 금지 목록, 6번째 넘기기, SVG, 20MiB 초과, 404, 제한 시간 | `LinkImageFetcherTest` 와 `ImageSourceEndpointTest` 의 까닭별 사례 | 각각 `IMAGE_SOURCE_LINK_BLOCKED`, `_NO_IMAGE`, `_TOO_LARGE`, `_FAILED` 와 정한 상태 코드 | 로컬 H2 Control Plane 통합 테스트와 단위 테스트 | worker | 없음 | PR 전 |
| REQ21 | 네 오류 코드를 주는 가짜 Control Plane | `test_image_source.py` 로 `prepare` 를 부른다 | 호출이 막히고 막는 글에 링크마다 까닭 글과 「그림을 만들지 않았다」 끝맺음이 있다 | 로컬 Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ22 | `source_links` 가 든 서명한 올리기 | `GeneratedImageEndpointTest`, `GeneratedImageTurnTest`, `test_generated_image.py` 의 `transform_tool_result` 경로 | 칸이 암호화되고 메시지 응답 `sourceLinks` 가 올린 순서다. Java 와 Python 의 서명 기대값이 같다 | 로컬 H2 통합 테스트와 Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ22 | `sourceLinks` 가 든 메시지 응답을 주는 브라우저 테스트 대역 | `pnpm --dir web test:browser generated-image.spec.ts` | 「그림 지시문 보기」 를 펼치면 링크가 보이고 새 창으로 열리는 `rel="noopener noreferrer"` 링크다. 넓은 화면과 휴대폰 폭 모두 | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ23 | 링크 사진을 받은 대화 | `ImageSourceEndpointTest` 에서 받기 뒤 그 대화의 첨부 목록, 결과물 폴더, 보관함을 본다. 사본 지우기는 `test_image_source.py` 의 `release` 사례 | 새 첨부, 결과물 파일, 보관본이 없고 사본과 기억한 링크가 지워진다. 같은 링크를 두 번 받으면 전송이 두 번 불린다 | 로컬 H2 통합 테스트와 Python 단위 테스트 | worker | 없음 | PR 전 |
| REQ20 | 배포한 운영, 실제 상품 페이지 링크 | 사용자 대화에서 링크를 보내고 그 상품을 넣은 그림을 부탁한다. 코디네이터가 같은 부탁을 Codex 결과와 나란히 사용자에게 보인다(스펙 DECISION42, 참고) | 그림에 상품 모양이 반영되고 「그림 지시문 보기」 에 링크가 보인다 | 운영 배포 뒤 실제 대화 | 코디네이터 | 배포(금지 목록 운영 값을 넣은 배포) | PR 뒤 |
