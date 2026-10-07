import assert from "node:assert/strict";
import { readdirSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

test("서로 다른 PR이 더한 Flyway 마이그레이션의 버전은 겹치지 않는다", () => {
  const files = readdirSync(
    join(import.meta.dirname, "../../backend/src/main/resources/db/migration"),
  );
  const versions = Map.groupBy(
    files.filter((file) => /^V.+__.+\.sql$/.test(file)),
    (file) => file.split("__")[0],
  );
  assert.deepEqual(
    [...versions.values()].filter((group) => group.length > 1),
    [],
    "겹친 새 마이그레이션의 버전을 적용 전에 다시 정한다",
  );
});
