# What is OryxOS

**OryxOS is a privately-deployed enterprise Agent Operating System (Agent Harness OS)** — letting every company run its own Agents, in natural language.

Deployed on a company's own K8s, VMs or bare metal, it runs all kinds of business Agents (ops assistant, support assistant, HR assistant, sales assistant, knowledge assistant…) on one unified harness, sharing channel access, model routing, tool invocation, memory and sandboxed execution. **Your data never leaves your own infrastructure. No cloud lock-in.**

Our belief: the bottleneck to making Agents reliable in production is usually not the model itself, but **the environment the Agent runs in**. OryxOS is not yet another Agent — it is the harness that lets a fleet of Agents run and collaborate reliably.

**North Star formula**:

```
Natural Language (md) + Memory + Tool + MCP (Connector) + Skill + Knowledge Base + Notify = An Agent
```

## Why OryxOS

Every company has work that belongs to Agents, yet most Agents never leave the demo stage — blocked by four barriers:

| Barrier | OryxOS answer |
|---------|---------------|
| Defining an Agent requires code — the people who know the business best can't build one | **Natural-language definition**: one directory with an `AGENT.md` is one Agent. Zero code |
| Cloud platforms take your data — compliance says no | **Private deployment**: data never leaves your domain, runs on your own machines |
| Execution is a black box — enterprises can't go to production | **Full-chain audit + sandbox**: every LLM/Tool call is persisted and queryable, whitelist-enforced isolation |
| Running one is easy, running a fleet is hard | **Agent Harness OS**: lifecycle and governance built for a whole team of Agents |

## Agent OS ≠ Agent runtime

The two terms are often conflated, but they are two different layers:

- **Agent runtime**: the execution kernel that runs a **single** Agent — LLM calls, tool execution, context management, loop control
- **Agent OS (Agent Harness OS)**: contains a runtime at its core, but manages a **fleet** of Agents on top — Agent lifecycles, unified inbound/outbound channels, unified memory, multi-tenancy and audit governance

By OS analogy: a runtime is like the execution environment of a single process; an Agent OS is the layer that manages a group of processes, schedules resources, and provides shared services and governance. In one sentence: **a runtime runs one Agent; an Agent OS runs and governs a fleet.** OryxOS is the latter.

Where it sits among neighbors:

| | Product | Who uses it | Relationship with OryxOS |
|---|---------|-------------|--------------------------|
| Agent frameworks (LangChain, Spring AI) | Code (libraries / SDKs) | Developers writing code | **Reuse**: OryxOS's LLM layer directly reuses Spring AI Alibaba |
| Orchestration platforms (Dify, Coze) | Executable workflows | Business/devs drag-and-drop | **Complementary**: orchestration platforms can run on top of OryxOS as the application layer |
| Big-tech middle platforms / SaaS | Complete applications | Business users | **Alternative**: OryxOS is open source, privately deployable, no cloud lock-in |
| **Agent OS (OryxOS)** | **Resident Agents defined by configuration** | Business configures + developers write Tools | —— |

In one sentence: frameworks give you materials to build your own house; orchestration platforms orchestrate "flows"; OryxOS is the finished house — a harness where Agents stay resident, governed and auditable.

## Key Features

- 🤖 **One directory = one Agent**: `AGENT.md` (frontmatter runtime config + body task instructions) defines an Agent. No code. Multiple Agents coexist in one instance
- ☕ **Java-native**: JDK 21 + Spring Boot 3.x, single executable JAR, seamlessly reusing enterprise Java ops tooling (Nacos, Sentinel, SkyWalking, Arthas, Prometheus + Grafana)
- 🔒 **Private and controllable**: data never leaves the enterprise, no cloud binding — the deterministic choice for heavily regulated industries (banking, government, telecom, energy, healthcare)
- 🛡️ **Security as foundation, not patch**: tool whitelist sandbox, credentials via env vars / enterprise key systems, `tool_invocations` / `llm_calls` audit tables persisted from day one
- 🧠 **Self-implemented ReAct engine**: the core reasoning loop is built in-house, no external Agent framework — loop behavior is fully controllable
- 🔌 **Open standards**: MCP for tools, A2A for Agent collaboration, Agent directories borrowing the Anthropic Agent Skills layout —协同 with the ecosystem, no private protocols
- 🧩 **Three-tier tool extension**: zero-code Agent directory + reuse MCP (recommended) → write your own MCP server in any language → `@Tool` Java Bean — pick by threshold
- 💾 **Cross-conversation memory**: session + long-term memory (`MEMORY.md`), interfaces reserve room for vector retrieval upgrades
- 🌐 **Stateless and scalable**: stateless instances with external state — the road to distribution is paved from day one

## Five Core Capabilities

| Capability | Description |
|------------|-------------|
| **LLM Integration** | Provider abstraction over mainstream LLMs (DeepSeek, Qwen, Kimi, Zhipu, Hunyuan, Doubao, Anthropic, OpenAI…). Agents are vendor-agnostic, switchable at runtime, local inference supported (Ollama, vLLM) |
| **ReAct Loop** | The Agent's reasoning engine: LLM thinks → calls tool → observes result → decides again, until a final response. Self-implemented, fully controllable |
| **Memory** | Session + long-term memory, remembering user preferences, project context and key decisions across conversations |
| **Tool System** | **15** built-in tools (file read/write/edit, shell, HTTP, web search and fetch, notifications, user interaction, long-term memory), Plugin Tool three-tier extension, whitelist sandbox |
| **Web Service** | 11 REST endpoints wrap every capability — any language that speaks HTTP can integrate; a read-only admin console and OpenAPI docs are served on the same port |
