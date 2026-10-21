# Quick Start

> ✅ **The runtime kernel is up**: all five core capabilities are delivered and the commands below are verified on real builds. Acceptance demos and cross-section integration checks are progressing per the [roadmap](/en/guide/roadmap).

## Requirements

- JDK 21+
- Maven 3.8+ (the wrapper `./mvnw` is included)

## Build & Run

```bash
# Build (always with clean; produces the executable fat JAR: oryxos-boot/target/oryxos.jar)
./mvnw clean package -Ddependency-check.skip=true

# Initialize the workspace (creates .oryxos/, idempotent; also creates tables and applies schema drift)
java -jar oryxos-boot/target/oryxos.jar init

# Create an Agent (generates a minimal .oryxos/agents/<name>/AGENT.md template)
java -jar oryxos-boot/target/oryxos.jar profile create my-assistant

# Interactive chat (requires model credentials)
export DEEPSEEK_API_KEY=sk-...
java -jar oryxos-boot/target/oryxos.jar chat

# Or run resident: REST API + admin console (schedules page can write) + OpenAPI docs, one process, one port
java -jar oryxos-boot/target/oryxos.jar serve
#   REST      http://localhost:8080/api/v1
#   Admin     http://localhost:8080/admin/
#   API docs  http://localhost:8080/swagger-ui
```

You can also use the bundled background scripts, which read credentials from a YAML file (`bin/config.yaml` is gitignored):

```bash
cp bin/config.yaml.example bin/config.yaml   # fill in deepseek.api-key
bin/start.sh                                 # start in background (runs init + health check)
bin/stop.sh                                  # stop
```

## One Executable, Multiple Subcommands

OryxOS ships a single artifact: the fat JAR `oryxos-boot/target/oryxos.jar`, whose main class is `io.oryxlabs.oryxos.cli.OryxOsCli` (Picocli). `java -jar oryxos.jar <subcommand>` goes through that one entry, and the subcommand decides whether a Spring context is started:

| Subcommand | Purpose |
|-----------|---------|
| `--version` / `status` / `profile …` (light commands) | Read files directly, no Spring context (fast startup) |
| `chat` | Interactive conversation |
| `serve` | Starts Spring Boot + web container: REST API (`/api/v1`), admin console (`/admin/`), OpenAPI docs (`/swagger-ui`), and scheduled tasks |
| `gateway` | Long-running mode that only runs scheduled tasks, no HTTP endpoints |

> Before lesson 18 there was also an `OryxosApplication` class. It was deleted in lesson 26 — its broad scan root collided with the CLI assembly, and `serve` already starts the web container, so it had no reason to exist.

## One Directory = One Agent

To define a daily-weather Agent, just write `.oryxos/agents/daily-weather/AGENT.md`:

```markdown
---
provider: { name: deepseek, model: deepseek-chat }
tools: [http_get, notify]
schedules: [{ cron: "0 0 8 * * *", message: "Check today's weather and give outfit advice" }]
---

Every morning, check the weather, generate outfit advice, and push it to the
team group via notify (channel name team-lark).
```

No code. Runs on schedule. Every call audited. The Agent name comes from the **directory name** — no `name` in frontmatter (it would be ignored).

The repo also ships a **complete** example with all four parts, ready to copy into a workspace:

- [`examples/agents/daily-reconcile/`](https://github.com/wngbob/oryxos-wngbob/tree/main/examples/agents/daily-reconcile): `AGENT.md` (frontmatter + four-step body), `REFERENCE.md`, `scripts/reconcile.py`, and a relative symlink `skills/report-format`
- [`examples/skills/report-format/SKILL.md`](https://github.com/wngbob/oryxos-wngbob/tree/main/examples/skills/report-format): the shared Skill entity

```bash
cp -a examples/skills/report-format   .oryxos/skills/
cp -a examples/agents/daily-reconcile .oryxos/agents/
```

Progressive disclosure is visible in this example: the body lives in the system prompt, while `REFERENCE.md` and the script are **not preloaded** — the model fetches them on demand with `read_file` / `shell`, so the script's output enters the context but its source does not.

::: tip Security note
Never write credentials (e.g. LLM API keys) in plaintext into `AGENT.md` frontmatter — inject them from environment variables via `${ENV_VAR}` placeholders.
:::
