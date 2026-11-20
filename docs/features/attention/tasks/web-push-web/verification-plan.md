# 웹 푸시 웹 부분 인수 검증 계획

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ1 | 알림 허락과 구독을 대역한 브라우저, 대역한 `/api/push/*` | `web/test/browser/push-settings.spec.ts` 의 등록 사례: 단추를 누르고 허락 | PUT 이 한 번 가고 목록에 기기 한 줄과 「이 기기」, 단추 대신 「이 기기는 알림을 받고 있어요」 | 로컬 Playwright(빌드한 서버) | worker | 없음 | PR 전 |
| REQ1 | 브라우저 구독은 있는데 서버 목록에 그 해시가 없음 | 같은 spec 의 지운 기기 사례: 화면을 연다 | 단추가 보이고 PUT 이 가지 않는다 | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ2 | 여러 user agent 문자열 | `web/test/unit/push.test.ts` 의 `deviceName` 표 | 「iPhone 앱」, 「Mac Chrome」 등 정한 이름, 같은 이름에 번호 없음 | node test | worker | 없음 | PR 전 |
| REQ3 | 거절, 미지원, 아이폰 Safari 탭, 홈 화면 앱, iOS 16.3, 서버 꺼짐 | `push-settings.spec.ts` 의 상태 사례와 `push.test.ts` 의 `pushScreenState` 표 | 거절과 미지원은 단추 없이 안내, 아이폰 탭은 안내 카드 셋, 홈 화면 앱은 단추, 16.3 은 버전 안내 | 로컬 Playwright, node test | worker | 없음 | PR 전 |
| REQ4 | `APP_NAME=검사용 비서` 로 뜬 웹 | `web/test/browser/web-app.spec.ts` | manifest 이름과 `standalone`, 아이콘 경로가 PNG 로 200 | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ5, REQ6 | 서비스 워커 파일 | `web/test/unit/push-service-worker.test.ts` | 푸시 글의 제목과 본문 그대로 표시, 바깥 주소 거절, 열린 창을 앞으로 가져와 옮김 | node test | worker | 없음 | PR 전 |
| REQ6 | 세션 없는 브라우저, 없는 알림 번호 | `web/test/browser/push-open.spec.ts` | 로그인 화면 `next` 에 `/n/<id>`, 없는 알림은 `/notifications` | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ6 | 승인 요청 알림 응답을 돌려주는 가짜 읽음 함수 | `web/test/unit/push-open.test.ts` 의 `openNotification` 사례 | 읽음을 그 id 로 한 번 부르고 `/chat/<대화>?todo=open` 으로 보낸다 | node test | worker | 없음 | PR 전 |
| REQ6 | `next` 후보 문자열 | `web/test/unit/push-open.test.ts` 의 `safeNextPath` | 다른 출처, `//`, 깨진 인코딩은 `/` 로 바뀐다 | node test | worker | 없음 | PR 전 |
| REQ6 | 세션 없는 브라우저, Google 요청 차단 | `push-open.spec.ts` 의 로그인 단추 사례 | `authjs.callback-url` 쿠키가 `/n/<id>`, 다른 출처 `next` 는 첫 화면 | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ9 | 구독 대역이 있는 로그인 브라우저 | 로그아웃 사례(`push-settings.spec.ts`) | 해제 DELETE 가 가고 로그인 화면. 해제가 멈춰도 2초 뒤 로그아웃 | 로컬 Playwright | worker | 없음 | PR 전 |
| REQ1, REQ2, REQ3, REQ4, REQ5, REQ6, REQ9 | VAPID 키를 넣은 운영 배포 | 아이폰 Safari 탭에서 「알림」 화면을 열고, 홈 화면에 추가해 앱으로 열고, 아이폰 앱과 PC Chrome 에서 기기를 등록한다. 승인 요청 알림 하나를 만들어 받고 누른다. PC 에서 로그인을 푼 뒤 받은 푸시를 누른다. 로그아웃한 뒤 알림을 하나 더 만든다 | Safari 탭에서 안내 카드 셋, 홈 화면에 앱 이름과 첫 글자 아이콘, 주소창 없는 화면, 앱에서 단추. 목록에 「iPhone 앱」, 「Mac Chrome」 같은 두 줄과 지금 기기의 「이 기기」. 두 기기에 시스템 알림이 뜨고 누르면 그 대화가 열리며 웹 알림 목록에서 그 줄이 읽음. 로그인이 풀린 PC 는 로그인 뒤 같은 대화로 간다. 로그아웃한 기기에는 오지 않고 목록에서 사라진다 | 운영 배포와 실제 기기 | 코디네이터 | 배포: 이 PR 과 백엔드, VAPID 키 | PR 뒤 |
