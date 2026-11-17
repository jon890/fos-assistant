/**
 * 네이버 화면의 상태 JSON 에서 칸을 찾는 위치다.
 *
 * **검색 화면은 2026-11-13 실제 화면으로 쟀다.** App Router 의 조각(`self.__next_f.push`)을 이은 글에서 `productName` 을 가진
 * 객체를 꺼내고, 그 칸 이름이 아래 `SEARCH_ITEM` 이다. **상품 화면(`PRODUCT_STATE_PATH`, `PRODUCT`)도 2026-11-13 실제 화면으로
 * 쟀다**(옵션 칸 제외: 그때 잰 상품이 모두 `optionUsable: false` 였다. 표준옵션 칸은 2026-11-16 에 쟀고, 조합형 옵션 칸 이름은 아직 가설이다). **가격비교 묶음 화면(`CATALOG`)은
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
 * 표준옵션 칸(`optionStandards`)과 상품 재고(`stockQuantity`)는 2026-11-16 옵션 있는 상품으로 쟀다.
 * 실측하지 못한 칸(조합형 옵션 묶음과 조합, `freeConditionalAmount`, `area3ExtraFee`)은 후보 이름일 뿐이고, 없으면 지어내지 않는다.
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
  /**
   * 표준옵션(색상·사이즈) 항목 배열(2026-11-16 실측, 구조만). 항목 칸은 `optionGroupName`, `optionName`, `imageInfo` 뿐이고
   * 가격·재고 칸이 없다. 그룹 이름별로 묶어 옵션 묶음으로 읽는다. 이 상품에는 `optionCombinations` 가 없었다.
   */
  standards: ["optionStandards"],
  standardGroup: ["optionGroupName"],
  standardValue: ["optionName"],
  /** 상품 전체 재고(2026-11-16 실측). 표준옵션 상품에서 0 이면 품절로 본다. */
  stock: ["stockQuantity"],
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
 * 상품 화면의 옵션 드롭다운 단추(2026-11-16 실측, 구조만). 색상·사이즈 표준옵션 상품에서 `aria-haspopup="listbox"` 단추가
 * 문서에 하나 보였고, 그 단추와 조상의 class 는 빌드마다 바뀌는 해시(`H4ArHznl5E` 꼴)라 class 로 고르지 않는다.
 * 상품 화면의 다른 드롭다운(리뷰 정렬 같은 것)을 누르지 않게 `OPEN_OPTION_LISTS` 가 범위를 좁힌다.
 * 보이는 단추가 하나면 그것을 누른다. 둘 이상이면 「구매하기」 단추(`PURCHASE_LAYER.buyLabel`)의 조상을 안쪽부터 올라가며 처음
 * 단추가 든 조상을 찾고, 그 안의 보이는 단추가 정확히 하나일 때만 누른다. 옵션 단추는 구매 단추와 같은 구매 상자에 있기 때문이다.
 * 그 조상에 단추가 둘 이상이면(페이지 래퍼까지 올라가 다른 드롭다운이 섞인 경우 포함) 어느 것인지 모르므로 누르지 않는다.
 * `body` 까지 가도 없거나 「구매하기」 가 없어도 누르지 않는다. 누르지 못하면 읽기는 `options_unknown` 으로 끝난다.
 */
export const PRODUCT_OPTION_BUTTON = 'button[aria-haspopup="listbox"]';

/**
 * 상품 구매 층. 상품 화면의 「구매하기」 를 누르면 같은 화면에 뜨고 옵션·수량과 「장바구니」, 「바로구매」, 「닫기」 가 있다(2026-11-13 실측).
 * 단추는 글이 정확히 같은 보이는 것만 고른다.
 *
 * 표준옵션 상품의 층(2026-11-17 실측, 구조만): 층은 `div[role=dialog]` 이고 옵션은 묶음마다 `[role=radiogroup]` 안의
 * `button[role=radio]` 칩이다. 처음엔 모두 `aria-checked="false"` 다. 색상 칩은 글이 없고 안의 `img` 의 `alt` 가 「<값>false」 꼴이며,
 * 사이즈 칩은 글이 값이다. 고르기 전에는 「바로구매」 가 `disabled` 다. 드롭다운(`optionButton`)·`role=option`·수량 칸은 이 층에 없었다.
 * 드롭다운 경로와 수량 칸은 아직 재지 않은 조합형 옵션 층을 위한 가설로 남긴다.
 */
export const PURCHASE_LAYER = {
  buyLabel: "구매하기",
  buyNowLabel: "바로구매",
  /** 「바로구매」 단추에서 가장 가까운 층. 없으면 문서 전체를 본다. 칩 경로는 층을 찾지 못하면 고르지 않는다. */
  layer:
    '[role="dialog"], [aria-modal="true"], [class*="layer" i], [class*="modal" i], [class*="sheet" i]',
  /** 옵션 묶음 하나. 층 안의 보이는 가장 바깥 묶음 차례가 옵션 글의 차례다(안쪽에 겹친 슬라이드용 묶음은 세지 않는다. 2026-11-17 실측). */
  radioGroup: '[role="radiogroup"]',
  /** 묶음 안의 값 칩. 글이 값이고, 글이 없으면 안의 `img` 의 `alt` 에서 끝의 `true`·`false` 를 뗀 것이 값이다. */
  radio: '[role="radio"]',
  radioImage: "img",
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

/**
 * 네이버페이 결제 내역 화면(2026-11-16 실측, 구조만). Next.js pages router 의 `__NEXT_DATA__` 에서
 * `props.pageProps.dehydratedState.queries[]` 가운데 `queryKey[0]` 이 `PAYMENT_LIST` 인 줄의 `state.data.pages[].items[]` 가 결제 목록이다.
 * 고정 식(`ORDER_HISTORY_STATE`)이 항목마다 `item` 의 칸만 골라 준다. 이름, 주소, 상품명, 판매처 칸은 탭 밖으로 꺼내지 않는다.
 * 금액 칸(`amounts`)과 `date` 의 뜻은 실제 결제가 없어 재지 못한 가설이다(design.md 의 「PR4 주문 확인(REQ14)」). 사람이 결제한 뒤 이 표만 고친다.
 */
export const ORDER_HISTORY = {
  queriesPath: ["props", "pageProps", "dehydratedState", "queries"],
  queryName: "PAYMENT_LIST",
  pagesPath: ["state", "data", "pages"],
  /** 항목 하나에서 고르는 칸. 값은 후보 경로 목록이고 `amounts` 만 후보마다 따로 꺼낸다. */
  item: {
    serviceType: [["serviceType"]],
    productNo: [["product", "no"]],
    productUrl: [["product", "infoUrl"], ["productDetailUrl"]],
    date: [["date"]],
    status: [["status", "name"]],
    /** 결제 금액 후보(가설). 하나라도 주문서의 최종 결제 금액과 같으면 맞다고 본다. 앞의 것이 결과에 싣는 금액이다. */
    amounts: [
      ["additionalData", "orderAmount"],
      ["product", "price"],
    ],
  },
};
/** 주문 확인이 보는 항목의 종류. 스마트스토어·브랜드스토어 주문이다. */
export const ORDER_SERVICE_TYPE = "ORDER";
/** 고정 식이 한 번에 돌려주는 항목의 상한. 첫 페이지는 15건이었다. */
export const ORDER_RAW_MAX = 100;
