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
  title: ["productName"],
  lowestPrice: ["lowestPrice"],
  deliveryFee: ["deliveryFee"],
  lowestPriceWithFee: ["lowestPriceWithFee"],
  /** 글이 있으면 지역 추가 배송비 안내가 있는 것으로 본다. */
  regionalNote: ["differentialDeliveryFeeContent"],
};
