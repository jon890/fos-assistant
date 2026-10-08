// web 코드의 lint 규칙이다. 규칙의 까닭은 각 규칙 블록의 주석이 갖고, 기준 파일(eslint-suppressions.json)과 포맷의 판단 규칙은 web/AGENTS.md 의 「lint 와 포맷」 절에 있다.
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

/**
 * `node --test` 가 읽는 파일과 그 파일이 import 하는 파일이다.
 * Node 는 tsconfig 의 `@/` 별칭을 풀지 못하므로, 이 파일들은 상대 경로 import 를 쓴다.
 * 새 단위 테스트가 web 파일을 읽으면 이 목록을 갱신한다.
 */
const NODE_TEST_READ_FILES = [
  "src/components/chat/activity/activity-state.ts",
  "src/components/chat/skill-command.ts",
  "src/lib/usage-paging.ts",
];

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),

  {
    // 오류 응답 모양과 요청 본문 검사를 한곳의 도우미로 모은다. 라우트가 직접 만들면 모양이 라우트마다 달라진다.
    files: ["src/app/api/**/*.ts"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "CallExpression[callee.object.name='NextResponse'][callee.property.name='json'] > ObjectExpression.arguments:first-child > Property:matches([key.name='code'], [key.value='code'])",
          message:
            "오류 응답을 `NextResponse.json({ code, ... })` 로 직접 만들지 않는다. 오류 응답 도우미를 쓴다.",
        },
        {
          selector:
            "CallExpression[callee.property.name='json'][callee.object.name=/^(request|req)$/]",
          message:
            "요청 본문을 `request.json()` 으로 직접 읽지 않는다. 요청 본문 도우미를 쓴다.",
        },
      ],
    },
  },

  {
    // 화면은 lib/ 의 호출 함수를 거친다. 화면 코드가 주소와 오류 처리를 각자 다시 쓰지 않게 한다.
    // 화면에서 fetch 를 꼭 써야 하는 파일이 생기면 이 블록의 ignores 에 그 파일과 까닭을 적는다.
    files: ["src/components/**/*.{ts,tsx}", "src/app/**/*.tsx"],
    rules: {
      "no-restricted-globals": [
        "error",
        {
          name: "fetch",
          message:
            "화면에서 fetch 를 직접 부르지 않는다. lib/ 의 호출 함수를 거친다.",
        },
      ],
    },
  },

  {
    // 파일을 옮겨도 import 가 깨지지 않게 상위 경로 대신 `@/` 를 쓴다.
    files: ["src/**/*.{ts,tsx}"],
    ignores: NODE_TEST_READ_FILES,
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            { group: ["../*"], message: "상위 경로 대신 `@/` 로 import 한다." },
          ],
        },
      ],
    },
  },

  {
    // 쪼갤 후보 목록이다. 실패시키지 않는다.
    files: ["src/**/*.{ts,tsx}"],
    rules: {
      "max-lines-per-function": [
        "warn",
        { max: 150, skipBlankLines: true, skipComments: true },
      ],
    },
  },
]);
