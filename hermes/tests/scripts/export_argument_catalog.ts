// 실제 Hermes plugin 검사는 Python fixture에 맡긴다.
import { runHermesFixture } from "../../../test/support/run-hermes-fixture.ts";
await runHermesFixture("export_argument_catalog.py");
