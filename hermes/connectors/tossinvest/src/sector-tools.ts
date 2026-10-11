import { z } from "zod";
import { envelopeMetadata } from "./api-contract.ts";
import { TossinvestError } from "./errors.ts";
import type { Tossinvest } from "./client.ts";
import type { RegisterTool } from "./tool-registration.ts";

const marketCountry = z.enum(["KR", "US"]);
const sectorId = z.string().regex(/^[A-Za-z0-9_-]{1,40}$/);
const duration = z.enum(["1d", "1w", "1mo", "3mo", "6mo", "1y"]);

export function registerSectorTools(
  register: RegisterTool,
  client: Tossinvest,
) {
  const read = async (path: string, query?: URLSearchParams) => {
    const envelope = await client.request(path, { query });
    return { result: envelope.result, metadata: envelopeMetadata(envelope) };
  };

  register("list_sectors", {}, { readOnlyHint: true }, () =>
    read("/api/v1/sectors"),
  );

  register(
    "get_sector_rankings",
    {
      type: z.enum(["TOP_GAINERS", "MARKET_TRADING_AMOUNT"]),
      marketCountry,
      duration,
    },
    { readOnlyHint: true },
    async ({ type, marketCountry, duration }) =>
      read(
        "/api/v1/sectors/rankings",
        new URLSearchParams({ type, marketCountry, duration }),
      ),
  );

  for (const [name, suffix] of [
    ["get_sector", ""],
    ["get_sector_stocks", "/stocks"],
    ["get_sector_etfs", "/etfs"],
  ] as const) {
    register(
      name,
      { sectorId, marketCountry },
      { readOnlyHint: true },
      async ({ sectorId, marketCountry }) => {
        if (name === "get_sector" && sectorId === "rankings")
          throw new TossinvestError("TOSSINVEST_INVALID_INPUT");
        return read(
          `/api/v1/sectors/${encodeURIComponent(sectorId)}${suffix}`,
          new URLSearchParams({ marketCountry }),
        );
      },
    );
  }
}
