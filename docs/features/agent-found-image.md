# 비서가 찾은 사진

비서가 웹에서 찾은 상품이나 사물, 장소의 사진을 그림의 바탕이나 참고 그림으로 쓰는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/chat/conversation/application/FoundLinks.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/ImageSourceService.java`, `backend/src/main/java/com/bifos/assistant/mcp/application/FoundLinkService.java`, `hermes/plugins/fos-ctx/found_links.py`

요구와 결정은 스펙 [`agent-found-image/spec.md`](agent-found-image/spec.md), 구조는 설계 [`agent-found-image/design.md`](agent-found-image/design.md) 가 갖는다.
비서가 웹 검색과 페이지 읽기 결과에 나온 주소를 `link:` 로 가리키면 사용자가 보낸 링크와 같은 검사로 그 사진을 받는다(PR1).
상품이나 사물, 장소의 이름만 들어도 비서가 사진을 찾아 고르고, 받지 못하면 다른 사진으로 다시 시도하며, 답에 출처 링크를 적는다. 찾은 사진을 쓴 그림은 「그림 지시문 보기」 의 참고한 링크에 「비서가 찾음」 이 붙는다(PR2).

## 흐름

1. 비서가 `web_search` 나 `web_extract` 를 부르면 fos-ctx 의 결과 hook 이 결과에서 주소를 뽑아 Control Plane 에 알린다. 결과 글은 바뀌지 않는다
2. Control Plane 은 서명한 호출 맥락으로 그 실행의 대화를 찾아 주소의 해시를 대화 행에 남긴다. 주소 원문은 남기지 않는다
3. 비서가 `image_generate` 의 바탕이나 참고에 `link:<결과에 나온 주소>` 를 적으면 fos-ctx 가 받기 경로로 보낸다
4. 받기 경로는 그 주소가 사용자 메시지에 있는지 보고, 없으면 그 대화의 해시에 있는지 본다. 둘 다 아니면 받지 않는다
5. 통과한 주소는 사용자가 보낸 링크와 같은 검사(공개 https 주소, 내부망과 금지 목록 차단, 사진 형식, 크기, 제한 시간)로 받는다
6. 해시로 통과한 주소면 받기 경로가 응답 머리로 알리고, fos-ctx 가 그림 올리기에 찾은 주소로 함께 보낸다. 그림 행은 링크마다 찾은 것인지 표시를 남기고 화면이 「비서가 찾음」 을 붙인다
7. 받지 못하면 fos-ctx 의 막는 글이 결과의 다른 사진으로 다시 부르게 한다. 그림 한 장에 다른 사진 3장까지이고, 끝내 없으면 그리지 않고 사진을 올리거나 링크를 보내는 길을 알린다

사용자가 보낸 링크, 올린 사진, 만든 그림을 쓰는 흐름은 [`generated-image.md`](generated-image.md) 가 갖는다.

## 찾은 주소로 보는 것

- 검색 결과에 나온 주소와, 읽은 페이지의 본문에 나온 주소다. 비서가 읽기 도구에 스스로 적어 넣은 주소 자체는 아니다
- 요청자의 그 대화에서 찾은 것만이다. 앞선 답에서 찾은 주소도 포함한다. 대화를 지우면 기록도 지운다
- 결과에 `&amp;`, `//`, 상대 주소 꼴로 적힌 주소는 Control Plane 이 정해진 대로 풀어 같은 주소로 본다. 비서는 완전한 주소로 적는다

같은 주소로 보는 정확한 규칙과 상한은 설계의 「같은 주소로 보는 규칙」 과 「저장」 이 갖는다.
