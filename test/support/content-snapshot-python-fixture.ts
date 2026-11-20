// 실제 Hermes plugin 검사는 Python fixture에 맡긴다.
import { runHermesFixture } from "./run-hermes-fixture.ts";
await runHermesFixture("content-snapshot-python-fixture.py");
