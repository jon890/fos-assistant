/** 공식 조회 operation과 사용자 도구의 대응. production 등록 코드와 독립된 기대값이다. */
export const readUnionCases: Array<{
  name: string;
  operations: string[];
  args: Record<string, unknown>;
}> = [
  { name: "list_accounts", operations: ["getAccounts"], args: {} },
  {
    name: "get_quotes",
    operations: ["getPrices", "getStocks"],
    args: { symbols: "AAPL" },
  },
  { name: "get_holdings", operations: ["getHoldings"], args: {} },
  {
    name: "get_sellable_quantity",
    operations: ["getSellableQuantity"],
    args: { symbol: "AAPL" },
  },
  {
    name: "get_buying_power",
    operations: ["getBuyingPower", "getSellableQuantity"],
    args: { currency: "KRW", symbol: "AAPL" },
  },
  { name: "list_orders", operations: ["getOrders"], args: { status: "OPEN" } },
  { name: "get_stocks", operations: ["getStocks"], args: { symbols: "AAPL" } },
  {
    name: "list_stocks",
    operations: ["listStocks"],
    args: { market: "NASDAQ", commonShare: false },
  },
  {
    name: "get_stock_warnings",
    operations: ["getStockWarnings"],
    args: { symbol: "AAPL" },
  },
  {
    name: "get_orderbook",
    operations: ["getOrderbook"],
    args: { symbol: "005930" },
  },
  { name: "get_trades", operations: ["getTrades"], args: { symbol: "AAPL" } },
  {
    name: "get_price_limits",
    operations: ["getPriceLimit"],
    args: { symbol: "AAPL" },
  },
  {
    name: "get_candles",
    operations: ["getCandles"],
    args: { symbol: "005930", interval: "1d" },
  },
  {
    name: "get_exchange_rate",
    operations: ["getExchangeRate"],
    args: { baseCurrency: "USD", quoteCurrency: "KRW" },
  },
  {
    name: "get_market_calendar",
    operations: ["getKrMarketCalendar"],
    args: { market: "KR" },
  },
  {
    name: "get_market_calendar",
    operations: ["getUsMarketCalendar"],
    args: { market: "US" },
  },
  { name: "list_sectors", operations: ["listSectors"], args: {} },
  {
    name: "get_sector_rankings",
    operations: ["getSectorRankings"],
    args: { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
  },
  {
    name: "get_sector",
    operations: ["getSector"],
    args: { sectorId: "100", marketCountry: "KR" },
  },
  {
    name: "get_sector_stocks",
    operations: ["getSectorStocks"],
    args: { sectorId: "100", marketCountry: "KR" },
  },
  {
    name: "get_sector_etfs",
    operations: ["getSectorEtfs"],
    args: { sectorId: "100", marketCountry: "KR" },
  },
  {
    name: "list_conditional_orders",
    operations: ["getConditionalOrders"],
    args: { status: "OPEN" },
  },
  {
    name: "get_conditional_order",
    operations: ["getConditionalOrder"],
    args: { conditionalOrderId: "read-union" },
  },
  {
    name: "get_rankings",
    operations: ["getRankings"],
    args: { type: "TOP_GAINERS", marketCountry: "KR", duration: "1d" },
  },
  {
    name: "get_market_indicator_prices",
    operations: ["getMarketIndicatorPrices"],
    args: { symbols: "KOSPI" },
  },
  {
    name: "get_market_indicator_candles",
    operations: ["getMarketIndicatorCandles"],
    args: { symbol: "KOSPI", interval: "1d" },
  },
  {
    name: "get_market_indicator_investor_trading",
    operations: ["getMarketIndicatorInvestorTrading"],
    args: { symbol: "KOSPI", interval: "1d" },
  },
  {
    name: "get_order",
    operations: ["getOrder"],
    args: { orderId: "read-union" },
  },
  { name: "get_commissions", operations: ["getCommissions"], args: {} },
  {
    name: "get_stock_investor_trading",
    operations: ["getStockInvestorTrading"],
    args: { symbol: "005930" },
  },
  {
    name: "get_stock_program_trades",
    operations: ["getStockProgramTrades"],
    args: { symbol: "005930" },
  },
  {
    name: "get_stock_short_selling",
    operations: ["getStockShortSelling"],
    args: { symbol: "005930" },
  },
  {
    name: "get_stock_credit_trades",
    operations: ["getStockCreditTrades"],
    args: { symbol: "005930" },
  },
  {
    name: "get_stock_securities_lending",
    operations: ["getStockSecuritiesLending"],
    args: { symbol: "005930" },
  },
];

export const readToolNames = [
  ...new Set(readUnionCases.map(({ name }) => name)),
].sort();
export const readOperationIds = [
  ...new Set(readUnionCases.flatMap(({ operations }) => operations)),
];
