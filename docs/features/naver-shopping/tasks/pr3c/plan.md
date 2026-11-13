# PR3c 계획: 주문서 준비·채우기 도구와 주문서 승인 카드

스펙 [`../../spec.md`](../../spec.md) 의 REQ10~REQ12 가운데 플러그인과 화면 쪽이다. 설계는 [`../../design.md`](../../design.md) 의 「도구(PR3c)」, 「채우기(REQ11, PR3c)」, 「옵션 드롭다운 열기(PR3c)」 다.
이 계획서는 구현 PR 에서 지운다.

## 작업

| 순서 | 작업 | 끝난 조건 |
| --- | --- | --- |
| 1 | 설계 「채우기」 의 단계와 주문서 칸을 2026-11-13 주문서 실측으로 고친다. 「구매하기 → 층의 바로구매」, 같은 탭과 새 탭, 금액 두 자리 대조, 금지 선택자 | 설계에 실측이 있고 「아직 재지 않았다」 가 없다 |
| 2 | `fields.ts` 에 주문서와 상품 구매 층의 칸, `expressions.ts` 에 `OPEN_OPTION_LISTS` 와 채우기 식(함수 선언 상수)을 더한다 | 식이 모두 상수이고 결제 단추 선택자와 「결제」 글이 없다 |
| 3 | `tab.ts` 를 탭 하나에서 일하는 함수로 넓히고 읽기 도구가 옵션 드롭다운을 한 번 연다 | `get_product_detail` 이 `optionUsable` 인데 옵션이 없을 때만 한 번 누른다 |
| 4 | `prepare_checkout`(상품 다시 읽기, 옵션 조합 맞추기, 요약)와 `fill_checkout`(원문·ticket 대조, 가격 확인, claim, 구매하기, 층의 옵션·수량, 바로구매, 주문서 금액) | 결과 모양이 Control Plane `ConnectorCheckoutSnapshot`, `ConnectorCheckoutOutcome` 과 대시보드 `_checkout` 의 검증을 지난다 |
| 5 | `assistant.json`(도구 둘, `execution_guard`, 오류 코드), README 도구 표, 스킬 | 계약 검사와 묶음 검사가 통과한다 |
| 6 | 웹: 승인 줄의 `checkout` 요약을 BFF 가 옮기고 주문서 카드가 그린다 | 카드에 배송지·결제 수단 칸이 없고 「지역 추가 배송비 별도」 와 오늘 남은 구매 한도가 있다 |
| 7 | 기능 파일의 도구 표와 「실제 계정으로 확인하기」 의 구매 절차, `user-browser/design.md` 의 한 줄 | 홈서버 정보가 없다 |
| 8 | 테스트: 가짜 CDP 의 상품·층·주문서 화면으로 채우기 순서, claim 거절, 금지 선택자, `UNKNOWN`, 읽기 도구의 누르기 한 번 | 플러그인 테스트와 웹 단위 테스트가 통과한다 |

## 검증

`bun run typecheck`, `bun run test`, `bun run check:bundle`(플러그인), `python -m pytest hermes/tests/test_connectors_contract.py`, 웹 단위 테스트, `scripts/dev/check-local.sh --fast`. 기능 루프 PR 이라 독립 verify 를 받는다.
