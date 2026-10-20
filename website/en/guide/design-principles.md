# Design Principles

Every technical decision in OryxOS traces back to these seven principles.

## Harness over Agents

The most important deliverable is not one powerful Agent, but an environment where any Agent can run reliably. A single Agent's ceiling is set by the model; whether a fleet of Agents can go to production is set by the harness.

## Own the core, control first

The core reasoning loop (ReActLoop) is self-implemented — no external Agent framework — so loop behavior is fully controllable. Mature libraries are reused for low-level model protocol adaptation (Spring AI Alibaba, protocol conversion + `@Tool` schema generation only). No wheel reinvention.

## Configuration as Agent

An Agent is defined by a configuration (a directory), not written in code. `AGENT.md`'s frontmatter is the runtime config and its body is the task instruction — the people who know the business best can define Agents without writing code.

## Open standards

MCP for tools, A2A for Agent collaboration, open formats for skills (borrowing the Anthropic Agent Skills directory layout) — cooperate with the ecosystem, never invent private protocols. Private protocols would lock users into OryxOS, which contradicts the project's positioning.

## Stateless instances, external state

Instances hold no state: sessions, audit and long-term memory (SQLite backend) live in SQLite; Agent directories and memory live in the file system, and scheduled tasks are defined in `AGENT.md`. This is the prerequisite for scaling smoothly from single-node to distributed — the road is paved from day one.

## Security is the foundation, not a patch

Controlled tool sources, least privilege, enforced sandbox, no credential persistence, full-chain audit — security lives in the architecture from day one, not as a shell added afterwards.

## Phased restraint

Today we build only the minimal complete set of the runtime kernel; governance and heavy distributed infrastructure come in later phases. Every architecture upgrade must be justified by real usage data — slow is fast: restrained and focused.
