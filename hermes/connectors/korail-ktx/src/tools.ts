import { logDiagnostic, ToolError } from "./errors.ts";
import { checkSearch, type SearchArgs } from "./input.ts";
import { MissingFields, parseTrains } from "./parse-trains.ts";
import { browserUrl, type Env } from "./session.ts";
import { searchInTab, type TabOptions } from "./tab.ts";
import { parseJson } from "./values.ts";

export type ToolDeps = {
  search: typeof searchInTab;
  tab?: TabOptions;
  now?: () => Date;
};
const DEFAULT_DEPS: ToolDeps = { search: searchInTab };

/** 인자를 확인한 뒤 한 번만 조회한다. 「다음」 을 읽지 않고 다시 부르지 않는다(spec DECISION8, DECISION9). */
export async function searchTrains(
  env: Env,
  args: SearchArgs,
  deps: ToolDeps = DEFAULT_DEPS,
) {
  const cdpUrl = browserUrl(env);
  const query = checkSearch(args, deps.now?.() ?? new Date());
  const raw = await deps.search(cdpUrl, query, deps.tab);
  try {
    const trains = parseTrains(parseJson(raw), query);
    return {
      query: {
        departure: query.departure,
        arrival: query.arrival,
        date: query.date,
        time_from: args.time_from,
        adults: query.adults,
        children: query.children,
        seniors: query.seniors,
        seat_class: query.seat_class,
      },
      count: trains.length,
      trains,
    };
  } catch (error) {
    if (!(error instanceof MissingFields)) throw error;
    logDiagnostic("search_trains", "parse", error.fields);
    throw new ToolError("KORAIL_PAGE_CHANGED");
  }
}
