/**
 * 네이버 화면의 상태 JSON 에서 칸을 찾는 위치다.
 *
 * **검색 화면은 2026-11-13 실제 화면으로 쟀다.** App Router 의 조각(`self.__next_f.push`)을 이은 글에서 `productName` 을 가진
 * 객체를 꺼내고, 그 칸 이름이 아래 `SEARCH_ITEM` 이다. 상품 화면(`PRODUCT_STATE_PATH`, `PRODUCT`)과 가격비교 묶음 화면
 * (`CATALOG_STATE_PATH`, `CATALOG`)은 **아직 실제 화면으로 확인하지 않은 가설이다.**
 * 칸마다 후보 이름을 차례로 보고, 필수 칸이 비면 결과를 지어내지 않고 `NAVER_SHOPPING_PAGE_CHANGED` 로 끝낸다.
 * 사용자가 실제 계정으로 확인한 뒤 이 표만 고친다(docs/features/naver-shopping.md 의 「실제 계정으로 확인하기」).
 */

/** 가격비교 묶음 화면(Next.js)의 `__NEXT_DATA__` 에서 묶음 상태까지의 경로. */
export const CATALOG_STATE_PATH = [
  "props",
  "pageProps",
  "initialState",
  "catalog",
];
/** 스마트스토어·브랜드스토어 상품 화면의 `window.__PRELOADED_STATE__` 에서 상품까지의 경로. */
export const PRODUCT_STATE_PATH = ["product", "A"];

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

/** 상품 화면의 상품. */
export const PRODUCT = {
  title: ["name", "productName"],
  store: [["channel", "channelName"], ["channelName"]],
  salePrice: ["salePrice"],
  discountedPrice: [
    ["benefitsView", "discountedSalePrice"],
    ["discountedSalePrice"],
  ],
  status: ["statusType", "productStatusType"],
  optionGroups: ["options"],
  groupName: ["groupName", "optionName"],
  combinations: ["optionCombinations"],
  combinationNames: ["optionName1", "optionName2", "optionName3"],
  combinationPrice: ["price"],
  combinationStock: ["stockQuantity"],
  delivery: ["productDeliveryInfo"],
  baseFee: ["baseFee", "deliveryFee"],
  freeOver: ["freeConditionalAmount"],
  regional: ["deliveryFeeByArea", "areaFee", "differentialFeeByArea"],
};
/** 바로 살 수 있는 상품 상태 값. */
export const SALE_STATUS = "SALE";

/** 가격비교 묶음 화면. */
export const CATALOG = {
  title: [["info", "productName"], ["productName"], ["info", "name"]],
  sellers: [["products"], ["mallList"], ["malls"]],
  store: ["mallName", "mallNm"],
  price: ["price", "lowPrice"],
  shippingFee: ["deliveryFee", "deliveryFeeContent"],
  url: ["pcProductUrl", "productUrl", "mallProductUrl"],
  naverPay: ["isNaverPay", "npayYn", "naverPayYn"],
};
