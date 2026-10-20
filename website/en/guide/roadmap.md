# Roadmap

Our development philosophy: **slow is fast — restrained and focused**. Nail the single-node runtime kernel first, then grow distributed capabilities step by step.

## Project Status

**Current phase: the runtime kernel is up.** All five core capabilities are delivered and `mvn clean verify` is green (578 tests); cross-section integration checks and the acceptance demos are in progress.

- [x] Industry research / requirements / technical solution / AI programming guide
- [x] Spec-Kit development workflow
- [x] Maven 9-module project skeleton (compiles and packages; fat JAR and 15 CLI subcommands runnable)
- [x] Core phase: five core capabilities (runtime kernel)
- [ ] Cross-section integration checks (lesson 27)
- [ ] Three acceptance demos: daily weather / daily tech digest / daily GitHub digest
- [ ] OryxOS 1.0 release

## Three Phases

### Phase 1 (Now) · Single-node Runtime Kernel

Five core capabilities are up: configuration-as-Agent, multi-Agent coexistence, REST API plus an admin console, MCP integration. Both trigger paths (human and scheduled), restart recovery and multi-Agent isolation are pinned by integration tests; what remains is wiring them into the acceptance demos.

### Phase 2 (Planned) · Distributed Harness

Stateless nodes, external state, multi-replica deployment — supporting larger scale and high availability.

### Phase 3 (Vision) · Cross-node Agent Collaboration

Introduce an Agent communication fabric over A2A: cross-node discovery, delegation, and reliable asynchronous coordination.

### Cross-cutting Capabilities

Built alongside each phase: multi-tenancy, SSO, complete audit, tool policies, observability, and a **fully writable** web console (the Phase 1 console only writes to the schedules page; write entry points such as channel management are deferred to the extension phase).

## Long-term Goal

**Join the Apache Software Foundation and become an Apache Top-Level Project.**

## Contributing

OryxOS is incubated by the oryx-labs community — an AI-coding-driven AI exploration community.

- Main development follows **Spec-Driven Development** (Spec-Kit): `specify → plan → tasks → implement`
- Incremental contributions (bug fixes, Plugin Tools, docs): edit the existing code and open a PR
- Before contributing, read the "Inviolable Principles" section of `CLAUDE.md` at the repo root — code that violates the red lines must be reworked

Join us on the [GitHub repository](https://github.com/wngbob/oryxos-wngbob) via Issues and PRs.
