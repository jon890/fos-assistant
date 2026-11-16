/**
 * 대화의 「에이전트 화면 보기」 단추를 보일지 정하는 순수 함수다. 판정은 `docs/features/browser-cowork/design.md` 의
 * 「PR2 에이전트 화면 보기」 가 갖는다. 이 파일은 상대 import 만 쓴다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */
import { bareName } from "./tool-label.ts";

const MCP_PREFIX = "mcp__";

/** 카탈로그에서 뽑은 사용자 브라우저를 쓰는 플러그인과 그 도구의 끝 이름이다. */
export type BrowserCatalog = {
  connectors: ReadonlySet<string>;
  tools: ReadonlySet<string>;
};

type CatalogItem = {
  id: string;
  ownerBrowser?: boolean;
  tools: { name: string }[];
};

export function browserCatalog(items: CatalogItem[]): BrowserCatalog {
  const owners = items.filter((item) => item.ownerBrowser === true);
  return {
    connectors: new Set(owners.map((item) => item.id)),
    tools: new Set(
      owners.flatMap((item) => item.tools.map((tool) => tool.name)),
    ),
  };
}

/** 카탈로그가 있어야 판정할 수 있는가. 도는 MCP 도구나 실행 중인 승인 줄이 있을 때만 카탈로그를 읽는다. */
export function needsBrowserCatalog(
  runningTools: (string | null)[],
  executing: string[],
): boolean {
  return (
    executing.length > 0 ||
    runningTools.some((name) => name !== null && name.startsWith(MCP_PREFIX))
  );
}

/** 이 대화에서 사용자 브라우저를 쓰는 플러그인 도구나 승인한 실행이 도는가. 카탈로그가 없으면 거짓이다. */
export function agentUsesBrowser(
  runningTools: (string | null)[],
  executing: string[],
  catalog: BrowserCatalog | null,
): boolean {
  if (catalog === null) return false;
  if (executing.some((id) => catalog.connectors.has(id))) return true;
  return runningTools.some((name) => {
    if (name === null || !name.startsWith(MCP_PREFIX)) return false;
    const bare = bareName(name);
    return bare !== null && catalog.tools.has(bare);
  });
}
