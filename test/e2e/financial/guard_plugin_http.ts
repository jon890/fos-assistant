// 실제 Hermes plugin 검사는 Python fixture에 맡긴다.
import { runHermesFixture } from "../../support/run-hermes-fixture.ts";
await runHermesFixture("guard_plugin_http.py");
