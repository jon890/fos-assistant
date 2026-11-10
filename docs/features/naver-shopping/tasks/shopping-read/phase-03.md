# Phase 03. 상품 검색과 상세 읽기, 사람이 해야 하는 화면

**Execution profile**: standard

## 목표

`search_products` 와 `get_product_detail` 이 「내 브라우저」 의 새 탭에서 네이버 화면을 읽고, 로그인·캡차·기기 인증 화면에서는 아무것도 누르지 않고 탭을 남긴 채 멈춘다.

**범위 외**: 화면 넘기기 카드(PR2).

## 컨텍스트

**요구사항**: REQ2, REQ3, REQ4

**근거 문서**: `docs/features/naver-shopping/design.md` 의 「주소 분류」, 「화면을 읽는 방식」, 「화면 분류」, `docs/features/naver-shopping/spec.md` 의 DECISION16 부터 DECISION21, DECISION28

- 탭 열기는 블로그 `src/editor/page.ts`(`/json/new`), 준비 기다리기는 `src/editor/open.ts` 의 `readyState` 폴링을 따른다

## 의도 메모

- 상태 JSON 칸 위치는 가설이다. `src/fields.ts` 한 곳에 모으고, 비면 지어내지 않고 `NAVER_SHOPPING_PAGE_CHANGED`
- 아래로 내리지 않는다(design DECISION9)
- `Runtime.evaluate` 식은 `src/expressions.ts` 상수뿐이다

## 작업 항목

### 1. 순수 모듈

`src/urls.ts`(주소 분류 표), `src/classify.ts`(화면 분류), `src/expressions.ts`, `src/fields.ts`, `src/parse-search.ts`, `src/parse-product.ts`(store, catalog).

### 2. 탭 읽기

`src/tab.ts`: 빈 탭 → `Page.enable` 과 대화상자 처리기 → `Page.navigate` → `readyState` → 분류 → 꺼내기 → 정상일 때만 닫기. 45초 제한. 실패 진단 줄은 칸 이름과 단계만.

### 3. 도구 연결

`src/server.ts` 의 두 도구를 채운다. 결과 자르기(글 200자, 판매처 50자, 40개, 옵션 20 묶음과 묶음마다 값 100개).

### 4. 테스트

`tests/tools.test.ts`(가짜 CDP 로 도구를 직접 불러 external, 거절, store, catalog 주소마다 탭 열기 횟수와 결과, `NAVER_SHOPPING_NOT_FOUND`, `options_truncated`), `tests/urls.test.ts`(주소와 화면 분류), `tests/parse.test.ts`(합성 fixture), `tests/tab.test.ts`(가짜 브라우저: 명령 순서, `Input.*` 없음, evaluate 식이 상수 집합 안, 사람이 해야 하는 분류에서 탭을 닫지 않음, 대화상자 닫기, 시간 초과), `tests/expressions.test.ts`(고정 식을 가짜 `document` 로 돌려 상태 JSON 꺼내기). 블로그의 Docker Chrome 실행기는 쓰지 않고 Gmail 처럼 `bun test` 로 돈다.

## 검증

```bash
cd hermes/connectors/naver-shopping && bun run typecheck && bun run test && bun run check:bundle
cd hermes/connectors/naver-shopping && bun test tests/tools.test.ts tests/urls.test.ts tests/parse.test.ts tests/tab.test.ts tests/expressions.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-shopping/src/urls.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/classify.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/expressions.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/fields.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/parse-search.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/parse-product.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/tab.ts` | 신규 |
| `hermes/connectors/naver-shopping/src/*.ts` | 신규·수정 (server.ts 는 phase 2 가 만든다) |
| `hermes/connectors/naver-shopping/tests/tools.test.ts` | 신규 |
| `hermes/connectors/naver-shopping/tests/urls.test.ts` | 신규 (주소 분류와 화면 분류) |
| `hermes/connectors/naver-shopping/tests/parse.test.ts` | 신규 (검색, 상품, 묶음 파서) |
| `hermes/connectors/naver-shopping/tests/tab.test.ts` | 신규 |
| `hermes/connectors/naver-shopping/tests/expressions.test.ts` | 신규 (고정 식을 가짜 document 로 돌린다) |
| `hermes/connectors/naver-shopping/tests/fake-browser.ts` | 신규 |
