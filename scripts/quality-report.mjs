// 품질 검사 보고서에서 사람이 따로 봐야 할 것을 뽑아 낸다.
// - eslint JSON 의 error: `--output-file` 을 주면 ESLint 가 표준 출력에 아무것도 내지 않아, 이 절이 없으면 어느 파일의 어느 규칙이 실패했는지 보이지 않는다.
// - 경고 목록: 실패시키지 않는 규칙(파일 길이, 메서드 길이 등)이 넘은 곳이다. 쪼갤 후보로 쓴다.
// Checkstyle 의 error 는 Gradle 출력에 보이므로 여기서 다루지 않는다.
//
// 사용법: node scripts/quality-report.mjs [--checkstyle <XML>]... [--eslint <JSON>] [--root <저장소 root>]
// 인자를 주지 않으면 기본 보고서 경로를 읽는다. 환경 변수 QUALITY_CHECKSTYLE_REPORTS(쉼표로 구분)와 QUALITY_ESLINT_REPORT 로도 바꾼다.
// 입력이 없으면 「보고서 없음」 으로 한 줄 내고, 이 스크립트는 늘 0 으로 끝난다. Node 내장 모듈만 쓴다.
import { existsSync, readFileSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

function parseArgs(argv) {
  const options = { checkstyle: [], eslint: undefined, root: undefined };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = argv[i + 1];
    if (flag === "--checkstyle" && value) {
      options.checkstyle.push(value);
      i++;
    } else if (flag === "--eslint" && value) {
      options.eslint = value;
      i++;
    } else if (flag === "--root" && value) {
      options.root = value;
      i++;
    }
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
const root = resolve(
  options.root ?? join(dirname(fileURLToPath(import.meta.url)), ".."),
);
const checkstyleReports = options.checkstyle.length
  ? options.checkstyle
  : process.env.QUALITY_CHECKSTYLE_REPORTS
    ? process.env.QUALITY_CHECKSTYLE_REPORTS.split(",")
    : [
        join(root, "backend/build/reports/checkstyle/main.xml"),
        join(root, "backend/build/reports/checkstyle/test.xml"),
      ];
const eslintReport =
  options.eslint ??
  process.env.QUALITY_ESLINT_REPORT ??
  join(root, "web/build/eslint-report.json");

function shortPath(file) {
  const rel = relative(root, isAbsolute(file) ? file : resolve(root, file));
  return rel.startsWith("..") ? file : rel;
}

function decodeXml(text) {
  return text.replace(/&(#x?[0-9a-fA-F]+|quot|apos|lt|gt|amp);/g, (_, ref) => {
    if (ref === "quot") return '"';
    if (ref === "apos") return "'";
    if (ref === "lt") return "<";
    if (ref === "gt") return ">";
    if (ref === "amp") return "&";
    const code = ref[1] === "x" ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
    return String.fromCodePoint(code);
  });
}

function attributes(tag) {
  const result = {};
  for (const match of tag.matchAll(/([\w:-]+)="([^"]*)"/g)) {
    result[match[1]] = decodeXml(match[2]);
  }
  return result;
}

// `...RegexpMultilineCheck#requiredArgsConstructor` 는 `#` 뒤의 id 를, 그 밖에는 클래스 이름에서 `Check` 를 뗀 것을 규칙 이름으로 쓴다.
function checkstyleRule(source) {
  const id = source.split("#")[1];
  if (id) return id;
  return source.split(".").pop().replace(/Check$/, "");
}

/** Checkstyle XML 에서 severity 가 warning 인 항목을 읽는다. 없는 파일은 건너뛴다. */
function readCheckstyleWarnings(files) {
  const found = files.filter((file) => existsSync(file));
  if (found.length === 0) return null;
  const warnings = [];
  for (const file of found) {
    const xml = readFileSync(file, "utf8");
    for (const block of xml.matchAll(/<file\s+([^>]*)>([\s\S]*?)<\/file>/g)) {
      const fileName = attributes(block[1]).name;
      for (const tag of block[2].matchAll(/<error\s+([^>]*?)\/?>/g)) {
        const error = attributes(tag[1]);
        if (error.severity !== "warning") continue;
        const rule = checkstyleRule(error.source ?? "");
        warnings.push({
          rule,
          location: `${shortPath(fileName)}:${error.line ?? 0}`,
          // 메시지가 규칙 이름을 `[이름] ` 으로 다시 달고 있으면 목록 머리와 겹치므로 뗀다.
          message: (error.message ?? "").replace(`[${rule}] `, ""),
        });
      }
    }
  }
  return warnings;
}

/** eslint JSON 에서 error(severity 2)와 경고(severity 1)를 나눠 읽는다. */
function readEslint(file) {
  if (!existsSync(file)) return null;
  const errors = [];
  const warnings = [];
  for (const result of JSON.parse(readFileSync(file, "utf8"))) {
    for (const message of result.messages) {
      const item = {
        rule: message.ruleId ?? "구문 오류",
        location: `${shortPath(result.filePath)}:${message.line ?? 0}`,
        message: message.message,
      };
      (message.severity === 2 ? errors : warnings).push(item);
    }
  }
  return { errors, warnings };
}

function printByRule(tool, items) {
  const byRule = new Map();
  for (const item of items) {
    if (!byRule.has(item.rule)) byRule.set(item.rule, []);
    byRule.get(item.rule).push(item);
  }
  for (const [rule, list] of byRule) {
    console.log(`  [${tool} ${rule}] ${list.length}건`);
    for (const item of list) console.log(`    ${item.location} ${item.message}`);
  }
}

const eslint = readEslint(eslintReport);
if (eslint && eslint.errors.length > 0) {
  console.log("실패한 위반(eslint)");
  printByRule("eslint", eslint.errors);
  console.log("");
}

console.log("경고 목록(실패 아님)");
const checkstyleWarnings = readCheckstyleWarnings(checkstyleReports);
if (checkstyleWarnings === null) {
  console.log("  Checkstyle: 보고서 없음");
} else if (checkstyleWarnings.length === 0) {
  console.log("  Checkstyle: 경고 없음");
} else {
  printByRule("Checkstyle", checkstyleWarnings);
}
if (eslint === null) {
  console.log("  eslint: 보고서 없음");
} else if (eslint.warnings.length === 0) {
  console.log("  eslint: 경고 없음");
} else {
  printByRule("eslint", eslint.warnings);
}
