/**
 * 네이버 화면의 상태 JSON 에서 칸을 찾는 위치다.
 *
 * **검색 화면은 2026-11-13 실제 화면으로 쟀다.** App Router 의 조각(`self.__next_f.push`)을 이은 글에서 `productName` 을 가진
 * 객체를 꺼내고, 그 칸 이름이 아래 `SEARCH_ITEM` 이다. **상품 화면(`PRODUCT_STATE_PATH`, `PRODUCT`)도 2026-11-13 실제 화면으로
 * 쟀다**(옵션 칸 제외: 잰 상품이 모두 `optionUsable: false` 라 옵션 칸 이름은 가설이다). **가격비교 묶음 화면(`CATALOG`)은
 * 2026-11-13 실측(판매처 목록 제외)이다.** 검색 화면처럼 조각에서 `nCatalogId` 와 `lowestPrice` 를 가진 객체를 꺼내고, 판매처별
 * 가격 목록은 어느 객체인지 가려내지 못해 읽지 않는다.
 * 칸마다 후보 이름을 차례로 보고, 필수 칸이 비면 결과를 지어내지 않고 `NAVER_SHOPPING_PAGE_CHANGED` 로 끝낸다.
 * 사용자가 실제 계정으로 확인한 뒤 이 표만 고친다(docs/features/naver-shopping.md 의 「실제 계정으로 확인하기」).
 */

/**
 * 스마트스토어·브랜드스토어 상품 화면의 `window.__PRELOADED_STATE__` 에서 상품까지의 경로(2026-11-13 실측).
 * 같은 상태의 `product` 는 칸만 있고 값이 비어 있다(`name` 없음, `salePrice` 0). 옛 후보 `product.A` 를 남기면 그 빈 객체를
 * 상품으로 오인할 수 있어 후보에 두지 않는다.
 */
export const PRODUCT_STATE_PATH = ["simpleProductForDetailPage"];

/**
 * 검색 결과 항목 하나(2026-11-13 실측). 고정 식이 상품 객체에서 이 칸들만 골라 평평한 배열로 준다.
 * 가격은 할인가가 있으면 할인가, 없으면 정가다. 광고는 `adId` 가 있거나 `cardType` 이 `AD` 다.
 */
export const SEARCH_ITEM = {
  title: ["productName"],
  price: ["discountedSalePrice", "salePrice"],
  store: ["mallName"],
  url: [["productUrl", "pcUrl"]],
  adId: ["adId"],
  cardType: ["cardType"],
  /** 같은 상품이 여러 칸에 실리면 이 값으로 한 번만 낸다. */
  productId: ["nvMid"],
};
/** 광고 카드의 `cardType` 값. */
export const AD_CARD_TYPE = "AD";

/**
 * 상품 화면의 상품. 실측한 칸: `name`(표시용 `dispName` 도 같은 값으로 실측했다), `channel.channelName`, `salePrice`, `benefitsView.discountedSalePrice`,
 * `productStatusType`, `optionUsable`, `productDeliveryInfo.{deliveryFeeType, baseFee, area2ExtraFee}`.
 * 실측하지 못한 칸(옵션 묶음과 조합, `freeConditionalAmount`, `area3ExtraFee`)은 후보 이름일 뿐이고, 없으면 지어내지 않는다.
 */
export const PRODUCT = {
  title: ["name", "dispName"],
  store: [["channel", "channelName"]],
  salePrice: ["salePrice"],
  discountedPrice: [["benefitsView", "discountedSalePrice"]],
  status: ["productStatusType"],
  /** false 면 옵션이 없는 상품이다. */
  optionUsable: ["optionUsable"],
  optionGroups: ["options"],
  groupName: ["groupName", "optionName"],
  combinations: ["optionCombinations"],
  combinationNames: ["optionName1", "optionName2", "optionName3"],
  combinationPrice: ["price"],
  combinationStock: ["stockQuantity"],
  delivery: ["productDeliveryInfo"],
  feeType: ["deliveryFeeType"],
  baseFee: ["baseFee"],
  freeOver: ["freeConditionalAmount"],
  /** 지역 추가 배송비. 0 보다 커야 추가 배송비가 있는 것으로 본다. */
  regional: ["area2ExtraFee", "area3ExtraFee"],
};
/** `deliveryFeeType` 의 무료 값. */
export const FREE_DELIVERY = "FREE";
/** 바로 살 수 있는 상품 상태 값. */
export const SALE_STATUS = "SALE";

/**
 * 가격비교 묶음 화면(2026-11-13 실측, 판매처 목록 제외). 고정 식이 `nCatalogId` 와 `lowestPrice` 를 함께 가진 객체에서 아래 칸만
 * 골라 준다. `lowestPrice` 는 숫자, `lowestPriceWithFee` 는 배송비를 포함한 최저가, `differentialDeliveryFeeContent` 는 지역 추가
 * 배송비 안내 글이다. 판매처별 가격 목록 칸은 확인하지 못해 두지 않는다.
 */
export const CATALOG = {
  /** 연 주소의 `/catalog/<번호>` 와 같아야 그 묶음이다. */
  catalogId: ["nCatalogId"],
  title: ["productName"],
  lowestPrice: ["lowestPrice"],
  deliveryFee: ["deliveryFee"],
  lowestPriceWithFee: ["lowestPriceWithFee"],
  /** 글이 있으면 지역 추가 배송비 안내가 있는 것으로 본다. */
  regionalNote: ["differentialDeliveryFeeContent"],
};

/**
 * 상품 화면의 옵션 자리(가설). 이 안의 `aria-haspopup="listbox"` 첫 단추만 누른다(`OPEN_OPTION_LISTS`).
 * 옵션이 있는 상품의 화면은 재지 않았다. 사용자가 실제 계정으로 확인한 뒤 고친다.
 */
export const PRODUCT_OPTION_BUTTON =
  '[class*="option" i] button[aria-haspopup="listbox"]';

/**
 * 상품 구매 층. 상품 화면의 「구매하기」 를 누르면 같은 화면에 뜨고 옵션·수량과 「장바구니」, 「바로구매」, 「닫기」 가 있다(2026-11-13 실측).
 * 단추 글은 실측이고, 층과 층 안의 옵션·수량 칸은 잰 상품에 옵션이 없어 가설이다. 단추는 글이 정확히 같은 보이는 것만 고른다.
 */
export const PURCHASE_LAYER = {
  buyLabel: "구매하기",
  buyNowLabel: "바로구매",
  /** 「바로구매」 단추에서 가장 가까운 층. 없으면 문서 전체를 본다. */
  layer:
    '[role="dialog"], [aria-modal="true"], [class*="layer" i], [class*="modal" i], [class*="sheet" i]',
  optionButton: 'button[aria-haspopup="listbox"]',
  /** 펼쳐진 옵션 드롭다운 단추. `aria-controls` 가 값 목록을 가리킨다. */
  openedOptionButton: 'button[aria-haspopup="listbox"][aria-expanded="true"]',
  optionItem: '[role="option"]',
  quantityInput: 'input[type="number"], input[inputmode="numeric"]',
};

/**
 * 주문서 화면(2026-11-13 실측). Next.js pages router 이고 금액이 든 상태 전역이 없어 화면 요소에서 읽는다.
 * CSS module 클래스라 뒤에 해시가 붙어 접두로 고른다. 두 자리의 금액이 같을 때만 최종 결제 금액으로 본다.
 * 결제 단추의 선택자는 여기에 두지 않는다. 금지 목록은 검사(`tests/checkout-guard.test.ts`)에만 있다.
 */
export const ORDER_SHEET = {
  host: "orders.pay.naver.com",
  pathPrefix: "/ordersheet/",
  /** 「총 주문금액」 줄의 금액. */
  total: 'dl[class*="ProductPrice_total"] dd[class*="ProductPrice_desc-total"]',
  /** 결제수단 절 머리의 같은 금액. */
  methodSummary: '#PAYMENT_WRAP [class*="ContentSection_summary"] em',
};
