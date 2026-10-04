# fos-assistant

English | [한국어](README.ko.md)

A personal AI assistant that belongs to the person using it, not to a model vendor or to any one product.
It is a self-hosted Control Plane and web app on top of [Hermes Agent](https://github.com/NousResearch/hermes-agent) by Nous Research, where you connect general-purpose connectors and your own agents into an agentic workflow of your own, backed by a memory that only keeps what a person has approved.
People who share a purpose, a family for example, use it together as a group, each with their own agents.

The product name is not final. For now it is called `fos-assistant`.

## A quick tour

The screens are in Korean, the only UI language for now.
Everything shown is made-up demo data: the pictures are taken by a script against the same local stack the browser tests use, never from a real deployment.

**Start a conversation.** Pick one of your agents, pick a tier (fast, balanced, or deep), and type or tap a suggested question.

![New conversation screen with three agent cards, the fast, balanced and deep tier buttons, and four suggested questions](docs/images/tour-01-new-conversation.png)

**Watch the work.** While an answer is being made, each tool call and each helper agent gets a line of its own.

![A running answer with one line per step: memory read, web search, two helper agents, and a web page being read](docs/images/tour-02-working.png)

**Approve before anything is written.** A connector call that writes to an outside service waits on a card that shows exactly what will be sent.

![Approval card for a connector write, showing the title and body of the note with approve and reject buttons](docs/images/tour-04-approval.png)

**Decide what is remembered.** An agent can only propose a memory. It is used in later conversations after you accept it.

![Memory screen with two proposed memories waiting for accept or reject, above the accepted group and personal memories](docs/images/tour-05-memory.png)

<details>
<summary>More screens: run tree, documents, agent settings, usage and cost</summary>

**Run tree.** Open a finished answer to see what it did, step by step, in a side panel.

![Finished answer with a table, and a side panel listing the run's tool calls and helper agents](docs/images/tour-03-run-tree.png)

**Documents and collections.** Longer notes live in collections. A sensitive document is encrypted at rest.

![Document form with a collection selector, and two saved documents, one marked sensitive](docs/images/tour-08-documents.png)

**Agent settings.** Write the agent's persona and switch its tools on and off.

![Agent detail screen with a persona text area and a list of tool switches](docs/images/tour-06-agent.png)

**Usage and cost.** An administrator sees runs per agent, with subscription runs converted to API prices.

![Usage screen with monthly totals and a per-agent table of runs, converted cost, and tokens](docs/images/tour-07-usage.png)

</details>

To take the pictures again, run `pnpm screenshots` in `web/`. The script is `test/screenshots/tour.shots.ts`.

## Why this exists

We want any individual to be free to have an assistant of their own, without depending on a particular model or product.
To get there, the goal is to let you connect general-purpose connectors into an agentic workflow that is yours.

- **Independent of the model.** A conversation picks a tier (fast, balanced, or deep), and the Control Plane database holds the policy that maps a tier to an actual model. The runtime is separate too: Hermes Agent runs the agents, and this repository decides who may use what, provides the web UI, and records what was used.
- **Independent of any product.** A connector is declared by a plugin's `connector.json`, and the Control Plane only has the generic flow. It does not know the name or the address of the service behind a connector, so adding one means writing a plugin with a `connector.json` and listing it in the Hermes dashboard's connector list, not changing the Control Plane or the web app ([ADR-043](docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
- **Yours.** You decide which tools and memory an agent has and which model a conversation uses.
- **It is the source of truth for long-term knowledge about you.** Agents and outside services read only the part they are allowed to. Service tokens are read-only, and the body of a sensitive entry is encrypted at rest.

General-purpose connectors live in this repository under `hermes/connectors/` and are maintained here ([ADR-064](docs/adr/ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md)).
The first one is Gmail: it searches and reads your mail without asking, and creates drafts, sends, replies and changes labels only after you approve. It never moves mail to the trash or deletes it.
A connector for a service that only one household or organization uses, such as the household account book attached today, stays in its own repository.
Growing the set of general-purpose connectors is where the work is headed. It is not done yet.

## How it compares

Other tools do some of this already, and several are far more mature.
The table says what each one is, in its own documentation's words where possible, and what this project does differently.

| Option | What it is | What is different here |
| --- | --- | --- |
| Hosted assistant apps, such as ChatGPT or Claude | Run by the model vendor, with nothing to install. Claude, for example, [saves memory as you chat](<https://support.claude.com/en/articles/11817273-using-claude-s-chat-search-and-memory-to-build-on-previous-context>) and lets you read, edit, and delete it in settings. | It runs on your own server. Which model a tier means is a policy row in your database. An agent can only propose a memory, and nothing is used until a person accepts it. |
| Self-hosted chat UIs, such as [Open WebUI](https://docs.openwebui.com/) or [LibreChat](https://www.librechat.ai/docs) | Open WebUI calls itself "a self-hosted AI platform" with "support for Ollama and OpenAI-compatible APIs". LibreChat is "a self-hosted web application" with agents, MCP, and custom endpoints. | This project does not call model APIs itself. It sits on an agent runtime and adds one isolated Hermes profile per person, collections that decide which agent receives which memory, an approval step for connector writes, delegation between agents with a run tree, and a ledger that converts usage to API prices. |
| [Hermes Agent](https://hermes-agent.nousresearch.com/docs/) on its own | An autonomous agent you reach from the CLI or a messaging gateway, with its own persistent memory, skills, and subagents. | It adds a web app for a group of people. The profile a run uses comes from the requester's binding. Memory goes through the Control Plane only, and Hermes' built-in memory tool is not given to agents. Outside services read through read-only service tokens, and sensitive entries are encrypted. Connectors follow one generic structure ([ADR-043](docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)). |

### Who it is for

It fits you if:

- you run your own server and want a family or another small group to share one assistant, each person with their own agents and memory,
- you want to change the model behind a conversation without changing the product you use,
- you want agents and connectors to do recurring work, with a person approving what gets written and remembered.

It does not fit you yet if:

- you want something that works without installing anything,
- you want a service anyone can sign up for,
- you are not ready to set up and operate Hermes Agent yourself. There is no step-by-step self-hosting guide yet, and the API and schema can still change. See [Status](#status).

## Principles

1. **Yours.** The person owns the agent's tools, memory, and model choice.
2. **Not mixed.** Someone else's memory and credentials never enter your run.
3. **Orchestrated.** Work is split across several agents and merged, rather than queued behind a single one. The agent decides what to split. The Control Plane decides who can see what.
4. **It remembers.** What was learned once is not asked again in the next conversation.

And one more that holds the others together: **it grows by adding people the same way every time.**
Adding a user is a single decision by an administrator, and it must not slow anyone else down or expose anything to them.

## What it does

- **Connectors with approval.** Register a personal token for an outside service and use an agent dedicated to it. A call that writes to that service runs only after you approve it, once, with exactly the arguments you approved.
- **Delegation.** One request can fan out to several agents, and the child runs still execute with the requester's permissions only.
- **Agents you build and share.** Create an agent from the web UI, write its persona, and choose its tools. Publish it to your group and others can talk to it, while each person's memory and conversations stay private.
- **Skills and `/commands`.** Upload skills to an agent and call one directly by typing `/` in the composer.
- **Model tiers.** Pick fast, balanced, or deep per conversation, or choose a model directly under advanced options.
- **Approval-based Memory.** An agent can only propose a memory. Nothing reaches a conversation until a person accepts it. Entries belong to collections, and each agent receives only the collections it is allowed to. Earlier revisions are kept when an entry is edited or deleted.
- **Run tree and cost.** Every run is recorded, including the ones that fail. Open a run to see its tool calls and child runs as a tree. Runs made on a subscription are also shown converted to API prices, so an administrator can compare settings.
- **Photos and HTML results.** Attach photos for an agent to read, and open the HTML pages an agent produces in a side panel. Scripts in those pages do not run.
- **Read-only access for other services.** A service token bound to a user lets another service read that user's documents and nothing else.

Some Memory screens, such as editing collections and viewing earlier revisions of an entry, are not built yet. The current list is in [`docs/code-architecture.md`](docs/code-architecture.md).
The full scope, with how each item is verified, is in [`docs/prd.md`](docs/prd.md).

## What it does not do

- **It does not modify Hermes core.** Only the official extension points are used: profiles, the API server, and plugin hooks.
- **It does not remember what no person has seen.** Hermes' built-in memory tool is not given to agents. The Control Plane is the only path to memory.
- **It does not store secrets in the database.** AI credentials and connector tokens stay on the Hermes side, and service tokens are stored only as hashes. Separate profiles do not by themselves mean separate AI accounts: an OAuth login can be shared across profiles. How credentials are separated or shared is in [`docs/hermes/README.md`](docs/hermes/README.md).
- **It is not an open sign-up service.** An administrator adds people to a group.
- **It does not run scripts in agent-made pages.**
- **It does not carry operating procedures.** Deployment and host-specific values belong to whoever runs it.

## Architecture

```mermaid
flowchart LR
    U[User] --> W["Web (web/)"]
    W --> C["Control Plane (backend/)"]
    C --> DB[(Database)]
    C --> A
    C --> G
    subgraph H[Hermes Agent]
        A[API server]
        P[Profile per user]
        G["Plugins (hermes/)"]
    end
    P -. "MCP calls, signed by a plugin" .-> C
```

| Layer | Responsibility |
| --- | --- |
| Hermes Agent | Agent execution, tool calls, subagents, sessions |
| Control Plane (`backend/`) | Users, agent access, Memory access, model routing, usage accounting |
| Web (`web/`) | Conversations, run status, Memory review, usage |
| Hermes add-ons (`hermes/`) | The plugins and profile template installed into your Hermes |

The profile a run uses is always taken from the requester's binding.
The request body cannot choose it.

## Status

This is an early-stage project.
One family actually uses it, and that is the only deployment so far.

- The public API and the database schema can still change without notice.
- Some decisions are recorded but not implemented yet. The [ADR index](docs/adr/INDEX.md) marks them.
- There is no step-by-step self-hosting guide yet.
- The roadmap is tracked in [issue #97](https://github.com/jon890/fos-assistant/issues/97).

## Getting started

### Requirements

| Tool | Version |
| --- | --- |
| Java | 21 |
| Node.js | 22.18 or later |
| pnpm | 10 |
| Python | 3.13 (for the Hermes plugin tests) |
| Hermes Agent | checked against v2026.9.24 |

### See the whole flow without a server

`test/e2e` starts a stand-in for the Hermes Runs API in the same process, so no Hermes installation is needed.
It boots the backend itself, so Java is still required.

```bash
node test/e2e/run.ts
```

It goes from issuing a login token to registering an agent, holding a conversation, and recording usage and cost.
The scenarios live one per file under `test/e2e/scenarios/`.

### Build the Hermes install bundle

`hermes/bundle.sh` builds an install bundle with the plugins and the profile template.

```bash
hermes/bundle.sh --out <directory> --mcp-url <Control Plane MCP URL>
```

[`hermes/README.md`](hermes/README.md) describes the bundle and the values it needs at install time.
Environment variables and the tech stack are in [`docs/self-hosting.md`](docs/self-hosting.md).

## Contributing

Issues and pull requests are welcome in English or Korean.
The internal documents (`docs/`, `AGENTS.md`, commit messages) are written in Korean, so the documents linked from this page are in Korean too.
How this project uses Hermes is described in [`docs/hermes/README.md`](docs/hermes/README.md).

- [`AGENTS.md`](AGENTS.md) has the rules of the repository, including what must never be written into a public repository.
- [`docs/README.md`](docs/README.md) is the index of all documents.
- [`docs/adr/INDEX.md`](docs/adr/INDEX.md) lists the decisions that are hard to reverse.
- `scripts/check-local.sh` runs the checks that CI runs. Before opening a pull request, pass the browser specs for the screens you changed; with no arguments it runs the whole browser suite. See the 「확인」 section of [`AGENTS.md`](AGENTS.md).

### Contributing a connector

A general-purpose connector is one directory, `hermes/connectors/<id>/`. A pull request that adds one needs the following.

- A `connector.json` with `schema: 2` that declares every tool the MCP server exposes, each with a risk and a reason for it in the connector's document.
- Calls that write require approval. Tools that send data to people outside the account also declare `"grant": false`, so each call is approved by a person. Tools that delete are not exposed.
- Secrets come in only through environment variables declared in `fields`, and the smallest OAuth scope or permission that works.
- An MCP server in Python that depends on nothing beyond the `mcp` SDK, and tests that run against a local fake of the service, never the real one.
- A setup guide under `docs/connectors/` and an owner line in `.github/CODEOWNERS`.

`hermes/tests/test_connectors_contract.py` checks the contract for every directory under `hermes/connectors/`, so a new connector is checked as soon as it is added.
The full guide is [`docs/connector-authoring.md`](docs/connector-authoring.md) (Korean), and [`hermes/connectors/gmail/`](hermes/connectors/gmail) is the reference to copy from.

## License

[Apache License 2.0](LICENSE)
