<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isEn = computed(() => lang.value.startsWith('en'))

/** 生成带 base 与语言前缀的站内链接 */
const L = (path) => withBase((isEn.value ? '/en' : '') + path)

const t = computed(() => (isEn.value ? en : zh))

const zh = {
  badge: '开源 · Apache 2.0 · oryx-labs 社区孵化',
  heroTitleA: '让企业用自然语言，',
  heroTitleB: '跑起来自己的 Agent',
  heroSub:
    'OryxOS 是企业私有部署的 Agent 操作系统（Agent Harness OS）——一个目录就是一个 Agent，一群 Agent 共享统一底座。数据完全留在企业自己的基础设施，不锁任何云生态。',
  ctaStart: '快速开始',
  ctaGithub: 'GitHub',
  chips: ['AGENT.md', 'MCP', 'ReAct', 'Memory', 'Sandbox'],
  formulaLabel: '北极星公式',
  formula: ['自然语言(md)', 'Memory', 'Tool', 'MCP(Connector)', 'Skill', '知识库', 'Notify'],
  formulaResult: '一个 Agent',
  why: {
    label: 'WHY',
    title: '为什么需要 OryxOS',
    desc: '每家公司都有该交给 Agent 的活，但 Agent 大多还停在 demo，卡在四道门槛上。',
    items: [
      {
        n: '01',
        title: '定义 Agent 要写代码',
        desc: '最懂业务的人反而做不了',
        fix: '自然语言定义：一个 AGENT.md 目录就是一个 Agent，零代码'
      },
      {
        n: '02',
        title: '云平台要把数据拿走',
        desc: '合规过不去',
        fix: '私有部署：数据不出域，装在企业自己的机器上'
      },
      {
        n: '03',
        title: '执行是黑盒',
        desc: '没审计、没隔离，企业不敢上生产',
        fix: '全链路审计 + 沙箱：每次 LLM/Tool 调用落库可查，白名单强制隔离'
      },
      {
        n: '04',
        title: '跑一个容易、跑一群难',
        desc: '没人把「一群 Agent 的操作系统」这一层交给你',
        fix: 'Agent Harness OS：为一整队 Agent 准备的生命周期与治理'
      }
    ]
  },
  concept: {
    label: 'CONCEPT',
    title: 'Agent OS ≠ Agent runtime',
    left: {
      name: 'Agent runtime',
      desc: '让单个 Agent 跑起来的执行内核——LLM 调用、工具执行、上下文管理、循环控制。'
    },
    right: {
      name: 'Agent OS',
      desc: '在 runtime 之上管一群 Agent——多 Agent 生命周期、统一的对外渠道与对内接入、统一的记忆、多租户与审计治理。'
    },
    punch: 'runtime 让一个 Agent 跑起来，Agent OS 让一群 Agent 在企业里被管起来。OryxOS 是后者。'
  },
  caps: {
    label: 'CAPABILITIES',
    title: '五大核心能力',
    items: [
      { name: '对接 LLM', desc: 'Provider 抽象统一对接主流大模型，运行时切换无锁定，支持本地推理' },
      { name: 'ReAct 循环', desc: '自实现推理引擎：思考 → 调工具 → 看结果 → 再决定，行为完全可控' },
      { name: 'Memory 记忆', desc: '会话 + 长期两层记忆，跨对话记住用户偏好、项目背景、关键决策' },
      { name: 'Tool 工具体系', desc: '内置 15 个工具，零代码到重代码三档扩展，白名单沙箱强制隔离' },
      { name: 'Web Service', desc: '27 个 REST 端点统一暴露全部能力；同一端口提供管理台（Agent 与定时任务页可写）与 OpenAPI 文档' }
    ]
  },
  arch: {
    label: 'ARCHITECTURE',
    title: '架构',
    bullets: [
      ['三触发源统一入口', 'CLI / Web Service（人推）与 AgentScheduler（钟推）汇入同一个 AgentService'],
      ['引擎调度三能力', 'ReActLoop 每轮经 PromptBuilder 组装上下文、经 ProviderService 调 LLM、经 ToolExecutor（先 Sandbox 校验）执行工具'],
      ['存储下沉', 'Session、审计与长期记忆（SQLite 档）落 SQLite；Agent 目录、MEMORY.md 落文件系统，git 可跟踪'],
      ['外部依赖在边界之外', 'LLM API、外部 MCP server、企业 IM webhook，OryxOS 不绑定任何一家']
    ],
    link: '查看完整架构说明 →'
  },
  principles: {
    label: 'PRINCIPLES',
    title: '设计原则',
    items: [
      ['底座优先于 Agent', '最重要的交付不是某个强大的 Agent，而是让任意 Agent 都能可靠运行的环境'],
      ['自实现核心，可控优先', '核心推理循环自己实现；底层协议适配复用成熟库，不重复造轮子'],
      ['配置即 Agent', '一个 Agent 由一份配置（一个目录）定义，而不是由代码写出'],
      ['对接开放标准', '工具用 MCP、协作用 A2A、技能用开放格式，与生态协同不另立协议'],
      ['无状态实例，状态外置', '从单机平滑走向分布式的前提'],
      ['安全是地基不是补丁', '工具来源受控、最小权限、强制沙箱、凭证不落地、全链路可审计'],
      ['分阶段克制', '只做运行时内核的最小完备集，每次架构升级都用真实数据证明必要性']
    ]
  },
  start: {
    label: 'QUICK START',
    title: '快速开始',
    note: '✅ 运行时内核已跑通：五大核心能力已交付，三个验收 Demo（每日天气 / 科技日报 / GitHub 日报）已跑通，以下命令均已在真实构建上验证。',
    comments: ['# 环境要求：JDK 21+、Maven 3.8+', '# 构建（必须带 clean）', '# 初始化工作区（幂等，含建表）', '# 创建一个 Agent', '# 常驻：REST /api/v1 + 管理台 /admin/ + 文档 /swagger-ui'],
    cmds: ['./mvnw clean package -Ddependency-check.skip=true', 'java -jar oryxos-boot/target/oryxos.jar init', 'java -jar oryxos-boot/target/oryxos.jar profile create my-assistant', 'java -jar oryxos-boot/target/oryxos.jar serve'],
    agentTitle: '一个目录 = 一个 Agent',
    agentDesc: '在 .oryxos/agents/daily-weather/AGENT.md 里写下配置，不写一行代码，到点自动运行，每次调用都有审计记录。'
  },
  roadmap: {
    label: 'ROADMAP',
    title: '路线图',
    motto: '慢就是快，克制且聚焦。先把单机的运行时内核做扎实，再逐步生长出分布式能力。',
    phases: [
      { tag: '阶段一 · 当前', name: '单机运行时内核', desc: '五大核心能力已跑通：配置即 Agent、动态管理 Agent（一句话 / 一次 API / 丢一个目录即上线）、多 Agent 并存、REST API 与管理台（Agent 与定时任务页可写）、对接 MCP；三个验收 Demo（天气 / 科技日报 / GitHub 日报）每天到点自己跑' },
      { tag: '阶段二 · 规划', name: '底座分布式', desc: '节点无状态化、状态外置、多副本部署，支撑更大规模与高可用' },
      { tag: '阶段三 · 愿景', name: '跨节点 Agent 协作', desc: '引入 Agent 通信底座，对接 A2A，跨节点发现、委托、可靠异步协同' }
    ],
    goal: '长期目标：走进 Apache 基金会，成为 Apache 顶级项目。'
  },
  cta: {
    title: '让 Agent 在你们企业真正跑起来',
    desc: '开源、私有部署、Java 原生。从一个 AGENT.md 目录开始。',
    btn: '从快速开始入手 →'
  },
  footer: {
    tagline: '企业 Agent 操作系统（Agent Harness OS）',
    cols: [
      { title: '文档', links: [['什么是 OryxOS', '/guide/what-is-oryxos'], ['快速开始', '/guide/quick-start'], ['架构', '/guide/architecture'], ['路线图', '/guide/roadmap']] },
      { title: '社区', links: [['GitHub', 'https://github.com/wngbob/oryxos-wngbob'], ['oryx-labs', 'https://github.com/oryx-labs/oryxos']] }
    ],
    license: 'Released under the Apache License 2.0.',
    copyright: 'Copyright © 2026 oryx-labs'
  }
}

const en = {
  badge: 'Open Source · Apache 2.0 · Incubated by oryx-labs',
  heroTitleA: 'Let every company run its own Agents,',
  heroTitleB: 'in natural language',
  heroSub:
    'OryxOS is a privately-deployed Agent Operating System (Agent Harness OS) — one directory defines one Agent, and a fleet of Agents shares one unified harness. Your data never leaves your own infrastructure. No cloud lock-in.',
  ctaStart: 'Quick Start',
  ctaGithub: 'GitHub',
  chips: ['AGENT.md', 'MCP', 'ReAct', 'Memory', 'Sandbox'],
  formulaLabel: 'North Star',
  formula: ['Natural Language (md)', 'Memory', 'Tool', 'MCP (Connector)', 'Skill', 'Knowledge Base', 'Notify'],
  formulaResult: 'An Agent',
  why: {
    label: 'WHY',
    title: 'Why OryxOS',
    desc: 'Every company has work that belongs to Agents, yet most Agents never leave the demo stage — blocked by four barriers.',
    items: [
      {
        n: '01',
        title: 'Defining an Agent requires code',
        desc: 'The people who know the business best can’t build one',
        fix: 'Natural-language definition: one AGENT.md directory is one Agent. Zero code'
      },
      {
        n: '02',
        title: 'Cloud platforms take your data',
        desc: 'Compliance says no',
        fix: 'Private deployment: data never leaves your domain, runs on your own machines'
      },
      {
        n: '03',
        title: 'Execution is a black box',
        desc: 'No audit, no isolation — enterprises can’t go to production',
        fix: 'Full-chain audit + sandbox: every LLM/Tool call is persisted and queryable, whitelist-enforced isolation'
      },
      {
        n: '04',
        title: 'Running one is easy, running a fleet is hard',
        desc: 'Nobody hands you the “operating system for a fleet of Agents” layer',
        fix: 'Agent Harness OS: lifecycle and governance built for a whole team of Agents'
      }
    ]
  },
  concept: {
    label: 'CONCEPT',
    title: 'Agent OS ≠ Agent runtime',
    left: {
      name: 'Agent runtime',
      desc: 'The execution kernel that runs a single Agent — LLM calls, tool execution, context management, loop control.'
    },
    right: {
      name: 'Agent OS',
      desc: 'Above the runtime, it manages a fleet — Agent lifecycles, unified inbound/outbound channels, unified memory, multi-tenancy and audit governance.'
    },
    punch: 'A runtime runs one Agent; an Agent OS runs and governs a fleet. OryxOS is the latter.'
  },
  caps: {
    label: 'CAPABILITIES',
    title: 'Five Core Capabilities',
    items: [
      { name: 'LLM Integration', desc: 'Provider abstraction over mainstream LLMs, switchable at runtime, local inference supported' },
      { name: 'ReAct Loop', desc: 'Self-implemented reasoning engine: think → act → observe → decide. Fully controllable' },
      { name: 'Memory', desc: 'Session + long-term memory that keeps user preferences and project context across conversations' },
      { name: 'Tool System', desc: '15 built-in tools, three extension tiers from zero-code to full-code, whitelist sandbox' },
      { name: 'Web Service', desc: '27 REST endpoints expose every capability; an admin console (writable Agent and schedules pages) and OpenAPI docs on the same port' }
    ]
  },
  arch: {
    label: 'ARCHITECTURE',
    title: 'Architecture',
    bullets: [
      ['One entry for three triggers', 'CLI / Web Service (human-pushed) and AgentScheduler (clock-pushed) converge on the same AgentService'],
      ['The engine orchestrates three capabilities', 'Each ReActLoop turn builds context via PromptBuilder, calls LLM via ProviderService, executes tools via ToolExecutor (sandbox-checked first)'],
      ['Storage sinks down', 'Sessions, audit and long-term memory (SQLite backend) live in SQLite; Agent directories and MEMORY.md live in the file system, git-trackable'],
      ['Dependencies stay outside the boundary', 'LLM APIs, external MCP servers and IM webhooks — OryxOS binds to none of them']
    ],
    link: 'Read the full architecture →'
  },
  principles: {
    label: 'PRINCIPLES',
    title: 'Design Principles',
    items: [
      ['Harness over Agents', 'The key deliverable is not one powerful Agent, but an environment where any Agent runs reliably'],
      ['Own the core, control first', 'The reasoning loop is self-implemented; mature libraries are reused for protocol adaptation'],
      ['Configuration as Agent', 'An Agent is defined by a configuration (a directory), not written in code'],
      ['Open standards', 'MCP for tools, A2A for collaboration, open formats for skills — no private protocols'],
      ['Stateless instances, external state', 'The prerequisite for scaling from single-node to distributed'],
      ['Security is the foundation, not a patch', 'Controlled tool sources, least privilege, enforced sandbox, no credential persistence, full audit'],
      ['Phased restraint', 'Only the minimal complete runtime kernel for now; every architecture upgrade must be justified by real usage data']
    ]
  },
  start: {
    label: 'QUICK START',
    title: 'Quick Start',
    note: '✅ Runtime kernel is up: all five core capabilities are delivered and the three acceptance demos (daily weather / tech digest / GitHub digest) are running; the commands below are verified on real builds.',
    comments: ['# Requires JDK 21+ and Maven 3.8+', '# Build (always with clean)', '# Initialize the workspace (idempotent, creates tables)', '# Create an Agent', '# Serve: REST /api/v1 + admin /admin/ + docs /swagger-ui'],
    cmds: ['./mvnw clean package -Ddependency-check.skip=true', 'java -jar oryxos-boot/target/oryxos.jar init', 'java -jar oryxos-boot/target/oryxos.jar profile create my-assistant', 'java -jar oryxos-boot/target/oryxos.jar serve'],
    agentTitle: 'One directory = one Agent',
    agentDesc: 'Write the config in .oryxos/agents/daily-weather/AGENT.md — no code, runs on schedule, every call audited.'
  },
  roadmap: {
    label: 'ROADMAP',
    title: 'Roadmap',
    motto: 'Slow is fast: restrained and focused. Nail the single-node runtime kernel first, then grow distributed capabilities.',
    phases: [
      { tag: 'Phase 1 · Now', name: 'Single-node Runtime Kernel', desc: 'Five core capabilities are up: configuration-as-Agent, dynamic Agent management (one sentence / one API call / one dropped directory goes live), multi-Agent coexistence, REST API plus admin console (writable Agent and schedules pages), MCP integration; three acceptance demos (weather / tech digest / GitHub digest) run on schedule' },
      { tag: 'Phase 2 · Planned', name: 'Distributed Harness', desc: 'Stateless nodes, external state, multi-replica deployment for scale and high availability' },
      { tag: 'Phase 3 · Vision', name: 'Cross-node Agent Collaboration', desc: 'Agent communication fabric over A2A: cross-node discovery, delegation, reliable async coordination' }
    ],
    goal: 'Long-term goal: join the Apache Software Foundation and become an Apache Top-Level Project.'
  },
  cta: {
    title: 'Make Agents actually work in your enterprise',
    desc: 'Open source, privately deployable, Java-native. Start with one AGENT.md directory.',
    btn: 'Start with Quick Start →'
  },
  footer: {
    tagline: 'Enterprise Agent Operating System (Agent Harness OS)',
    cols: [
      { title: 'Docs', links: [['What is OryxOS', '/guide/what-is-oryxos'], ['Quick Start', '/guide/quick-start'], ['Architecture', '/guide/architecture'], ['Roadmap', '/guide/roadmap']] },
      { title: 'Community', links: [['GitHub', 'https://github.com/wngbob/oryxos-wngbob'], ['oryx-labs', 'https://github.com/oryx-labs/oryxos']] }
    ],
    license: 'Released under the Apache License 2.0.',
    copyright: 'Copyright © 2026 oryx-labs'
  }
}
</script>

<template>
  <div class="ox-home">
    <!-- ============ HERO ============ -->
    <section class="ox-hero">
      <div class="ox-wrap ox-hero-grid">
        <div class="ox-hero-copy">
          <span class="ox-badge">{{ t.badge }}</span>
          <h1 class="ox-hero-title">
            {{ t.heroTitleA }}<br />
            <span class="ox-hero-accent">{{ t.heroTitleB }}</span>
          </h1>
          <p class="ox-hero-sub">{{ t.heroSub }}</p>
          <div class="ox-hero-cta">
            <a class="ox-btn ox-btn-primary" :href="L('/guide/quick-start')">{{ t.ctaStart }}</a>
            <a class="ox-btn ox-btn-ghost" href="https://github.com/wngbob/oryxos-wngbob" target="_blank" rel="noopener">{{ t.ctaGithub }} →</a>
          </div>
        </div>
        <div class="ox-hero-visual">
          <div class="ox-logo-card">
            <img :src="withBase('/images/logo-icon.svg')" alt="OryxOS" class="ox-logo-img" />
          </div>
          <span v-for="(chip, i) in t.chips" :key="chip" class="ox-chip" :class="`ox-chip-${i}`">{{ chip }}</span>
        </div>
      </div>
    </section>

    <!-- ============ 北极星公式 ============ -->
    <section class="ox-formula">
      <div class="ox-wrap">
        <span class="ox-formula-label">{{ t.formulaLabel }}</span>
        <p class="ox-formula-line">
          <template v-for="(item, i) in t.formula" :key="item">
            <code class="ox-formula-chip">{{ item }}</code>
            <span class="ox-formula-op">+</span>
          </template>
          <span class="ox-formula-op">=</span>
          <code class="ox-formula-chip ox-formula-result">{{ t.formulaResult }}</code>
        </p>
      </div>
    </section>

    <!-- ============ WHY ============ -->
    <section class="ox-section">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.why.label }}</p>
        <h2 class="ox-title">{{ t.why.title }}</h2>
        <p class="ox-section-desc">{{ t.why.desc }}</p>
        <div class="ox-grid ox-grid-4">
          <div v-for="item in t.why.items" :key="item.n" class="ox-card">
            <span class="ox-card-num">{{ item.n }}</span>
            <h3 class="ox-card-title">{{ item.title }}</h3>
            <p class="ox-card-desc">{{ item.desc }}</p>
            <p class="ox-card-fix">{{ item.fix }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ CONCEPT ============ -->
    <section class="ox-section ox-section-soft">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.concept.label }}</p>
        <h2 class="ox-title">{{ t.concept.title }}</h2>
        <div class="ox-grid ox-grid-2">
          <div class="ox-card ox-compare">
            <h3 class="ox-compare-name">{{ t.concept.left.name }}</h3>
            <p class="ox-card-desc">{{ t.concept.left.desc }}</p>
          </div>
          <div class="ox-card ox-compare ox-compare-hi">
            <h3 class="ox-compare-name">{{ t.concept.right.name }}</h3>
            <p class="ox-card-desc">{{ t.concept.right.desc }}</p>
          </div>
        </div>
        <p class="ox-punch">{{ t.concept.punch }}</p>
      </div>
    </section>

    <!-- ============ CAPABILITIES ============ -->
    <section class="ox-section">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.caps.label }}</p>
        <h2 class="ox-title">{{ t.caps.title }}</h2>
        <div class="ox-grid ox-grid-5">
          <div v-for="(item, i) in t.caps.items" :key="item.name" class="ox-card ox-cap">
            <span class="ox-card-num">{{ String(i + 1).padStart(2, '0') }}</span>
            <h3 class="ox-card-title">{{ item.name }}</h3>
            <p class="ox-card-desc">{{ item.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ ARCHITECTURE ============ -->
    <section class="ox-section ox-section-soft">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.arch.label }}</p>
        <h2 class="ox-title">{{ t.arch.title }}</h2>
        <div class="ox-arch">
          <a :href="withBase('/images/docs-architecture-light.svg')" target="_blank" rel="noopener" class="ox-arch-imgbox">
            <img :src="withBase('/images/docs-architecture-light.svg')" :alt="t.arch.title" class="ox-arch-img" />
          </a>
          <ul class="ox-arch-list">
            <li v-for="b in t.arch.bullets" :key="b[0]">
              <strong>{{ b[0] }}</strong>
              <span>{{ b[1] }}</span>
            </li>
          </ul>
        </div>
        <a class="ox-more" :href="L('/guide/architecture')">{{ t.arch.link }}</a>
      </div>
    </section>

    <!-- ============ PRINCIPLES ============ -->
    <section class="ox-section">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.principles.label }}</p>
        <h2 class="ox-title">{{ t.principles.title }}</h2>
        <div class="ox-grid ox-grid-2">
          <div v-for="p in t.principles.items" :key="p[0]" class="ox-principle">
            <h3 class="ox-principle-title">{{ p[0] }}</h3>
            <p class="ox-card-desc">{{ p[1] }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ QUICK START ============ -->
    <section class="ox-section ox-section-soft">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.start.label }}</p>
        <h2 class="ox-title">{{ t.start.title }}</h2>
        <p class="ox-section-desc">{{ t.start.note }}</p>
        <div class="ox-start-grid">
          <div class="ox-terminal">
            <div class="ox-terminal-bar">
              <span class="ox-terminal-dot"></span>
              <span class="ox-terminal-dot"></span>
              <span class="ox-terminal-dot"></span>
            </div>
            <pre class="ox-terminal-body"><code><span class="ox-c">{{ t.start.comments[0] }}</span>
<span class="ox-c">{{ t.start.comments[1] }}</span>
<span class="ox-p">$</span> {{ t.start.cmds[0] }}
<span class="ox-c">{{ t.start.comments[2] }}</span>
<span class="ox-p">$</span> {{ t.start.cmds[1] }}
<span class="ox-c">{{ t.start.comments[3] }}</span>
<span class="ox-p">$</span> {{ t.start.cmds[2] }}
<span class="ox-c">{{ t.start.comments[4] }}</span>
<span class="ox-p">$</span> {{ t.start.cmds[3] }}</code></pre>
          </div>
          <div class="ox-card ox-agent-card">
            <h3 class="ox-card-title">{{ t.start.agentTitle }}</h3>
            <p class="ox-card-desc">{{ t.start.agentDesc }}</p>
            <pre class="ox-agent-yaml"><code>---
name: daily-weather
provider: { name: deepseek, model: deepseek-chat }
tools: [http_get, notify]
schedules: [{ cron: "0 0 8 * * *", message: "..." }]
---</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ ROADMAP ============ -->
    <section class="ox-section">
      <div class="ox-wrap">
        <p class="ox-label">{{ t.roadmap.label }}</p>
        <h2 class="ox-title">{{ t.roadmap.title }}</h2>
        <p class="ox-section-desc">{{ t.roadmap.motto }}</p>
        <div class="ox-grid ox-grid-3">
          <div v-for="p in t.roadmap.phases" :key="p.tag" class="ox-card">
            <span class="ox-phase-tag">{{ p.tag }}</span>
            <h3 class="ox-card-title">{{ p.name }}</h3>
            <p class="ox-card-desc">{{ p.desc }}</p>
          </div>
        </div>
        <p class="ox-punch">{{ t.roadmap.goal }}</p>
      </div>
    </section>

    <!-- ============ CTA ============ -->
    <section class="ox-cta">
      <div class="ox-wrap ox-cta-box">
        <h2 class="ox-cta-title">{{ t.cta.title }}</h2>
        <p class="ox-cta-desc">{{ t.cta.desc }}</p>
        <a class="ox-btn ox-btn-primary ox-btn-lg" :href="L('/guide/quick-start')">{{ t.cta.btn }}</a>
      </div>
    </section>

    <!-- ============ FOOTER ============ -->
    <footer class="ox-footer">
      <div class="ox-wrap ox-footer-grid">
        <div class="ox-footer-brand">
          <img :src="withBase('/images/logo-icon.svg')" alt="OryxOS" class="ox-footer-logo" />
          <div>
            <p class="ox-footer-name">OryxOS</p>
            <p class="ox-footer-tagline">{{ t.footer.tagline }}</p>
          </div>
        </div>
        <div v-for="col in t.footer.cols" :key="col.title" class="ox-footer-col">
          <p class="ox-footer-col-title">{{ col.title }}</p>
          <a
            v-for="l in col.links"
            :key="l[0]"
            :href="l[1].startsWith('http') ? l[1] : L(l[1])"
            :target="l[1].startsWith('http') ? '_blank' : undefined"
            rel="noopener"
            class="ox-footer-link"
          >{{ l[0] }}</a>
        </div>
      </div>
      <div class="ox-wrap ox-footer-bottom">
        <span>{{ t.footer.license }}</span>
        <span>{{ t.footer.copyright }}</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.ox-home {
  background: var(--ox-bg);
  color: var(--ox-text);
}
.ox-wrap {
  max-width: 1080px;
  margin: 0 auto;
  padding: 0 24px;
}

/* ---------- Hero ---------- */
.ox-hero {
  position: relative;
  padding: 96px 0 72px;
  background:
    radial-gradient(600px 320px at 82% 18%, var(--ox-glow), transparent 70%),
    var(--ox-bg);
  overflow: hidden;
}
.ox-hero-grid {
  display: grid;
  grid-template-columns: 1.25fr 0.75fr;
  gap: 48px;
  align-items: center;
}
.ox-badge {
  display: inline-block;
  font-size: 13px;
  color: var(--ox-brand-deep);
  background: var(--ox-glow);
  border: 1px solid var(--ox-border);
  border-radius: 999px;
  padding: 4px 14px;
  margin-bottom: 24px;
}
.ox-hero-title {
  font-size: 46px;
  line-height: 1.2;
  font-weight: 800;
  letter-spacing: -0.5px;
  margin: 0 0 20px;
}
.ox-hero-accent {
  color: var(--ox-brand-deep);
}
.ox-hero-sub {
  font-size: 17px;
  line-height: 1.8;
  color: var(--ox-text-2);
  max-width: 560px;
  margin: 0 0 32px;
}
.ox-hero-cta {
  display: flex;
  gap: 14px;
}
.ox-btn {
  display: inline-block;
  font-size: 15px;
  font-weight: 600;
  border-radius: 10px;
  padding: 11px 24px;
  text-decoration: none !important;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.ox-btn-primary {
  background: var(--ox-brand);
  color: #1a1205 !important;
  box-shadow: 0 8px 20px -8px rgba(245, 158, 11, 0.55);
}
.ox-btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 26px -8px rgba(245, 158, 11, 0.65);
}
.ox-btn-ghost {
  border: 1px solid var(--ox-border);
  color: var(--ox-text) !important;
  background: var(--ox-card);
}
.ox-btn-ghost:hover {
  border-color: var(--ox-brand);
}
.ox-btn-lg {
  font-size: 16px;
  padding: 13px 30px;
}

.ox-hero-visual {
  position: relative;
  display: flex;
  justify-content: center;
}
.ox-logo-card {
  width: 216px;
  height: 216px;
  border-radius: 28px;
  background: linear-gradient(135deg, #22314f, #0c1424);
  box-shadow: 0 28px 64px -24px rgba(12, 20, 36, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
}
.ox-logo-img {
  width: 100%;
  height: 100%;
  border-radius: 28px;
}
.ox-chip {
  position: absolute;
  font-family: var(--vp-font-family-mono);
  font-size: 12px;
  color: var(--ox-text-2);
  background: var(--ox-card);
  border: 1px solid var(--ox-border);
  border-radius: 8px;
  padding: 4px 10px;
  box-shadow: 0 6px 16px -8px rgba(15, 23, 42, 0.25);
  animation: ox-float 5s ease-in-out infinite;
}
.ox-chip-0 { top: -6px; left: 6%; animation-delay: 0s; }
.ox-chip-1 { top: 22%; right: -4%; animation-delay: 0.8s; }
.ox-chip-2 { bottom: 26%; left: -6%; animation-delay: 1.6s; }
.ox-chip-3 { bottom: -4px; right: 14%; animation-delay: 2.4s; }
.ox-chip-4 { top: 48%; left: -12%; animation-delay: 3.2s; }
@keyframes ox-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

/* ---------- 北极星公式 ---------- */
.ox-formula {
  border-top: 1px solid var(--ox-border);
  border-bottom: 1px solid var(--ox-border);
  background: var(--ox-bg-soft);
  padding: 26px 0;
  text-align: center;
}
.ox-formula-label {
  display: block;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 3px;
  color: var(--ox-brand-deep);
  margin-bottom: 12px;
}
.ox-formula-line {
  margin: 0;
  font-size: 14px;
  line-height: 2.4;
}
.ox-formula-chip {
  font-family: var(--vp-font-family-mono);
  background: var(--ox-card);
  border: 1px solid var(--ox-border);
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 13.5px;
  color: var(--ox-text);
}
.ox-formula-result {
  border-color: var(--ox-brand);
  color: var(--ox-brand-deep);
  font-weight: 700;
}
.ox-formula-op {
  color: var(--ox-text-2);
  margin: 0 6px;
}

/* ---------- 通用区块 ---------- */
.ox-section {
  padding: 84px 0;
}
.ox-section-soft {
  background: var(--ox-bg-soft);
}
.ox-label {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--ox-brand-deep);
  margin: 0 0 10px;
}
.ox-title {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.5px;
  margin: 0 0 14px;
  border: none;
  padding: 0;
}
.ox-section-desc {
  font-size: 16px;
  color: var(--ox-text-2);
  max-width: 720px;
  margin: 0 0 36px;
}
.ox-grid {
  display: grid;
  gap: 18px;
}
.ox-grid-4 { grid-template-columns: repeat(4, 1fr); }
.ox-grid-3 { grid-template-columns: repeat(3, 1fr); }
.ox-grid-2 { grid-template-columns: repeat(2, 1fr); }
.ox-grid-5 { grid-template-columns: repeat(5, 1fr); }

.ox-card {
  background: var(--ox-card);
  border: 1px solid var(--ox-border);
  border-radius: 14px;
  padding: 22px;
  transition: border-color 0.15s ease, transform 0.15s ease;
}
.ox-card:hover {
  border-color: var(--ox-brand);
  transform: translateY(-2px);
}
.ox-card-num {
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
  font-weight: 700;
  color: var(--ox-brand-deep);
}
.ox-card-title {
  font-size: 16.5px;
  font-weight: 700;
  margin: 10px 0 8px;
}
.ox-card-desc {
  font-size: 14px;
  line-height: 1.7;
  color: var(--ox-text-2);
  margin: 0;
}
.ox-card-fix {
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ox-text);
  border-top: 1px dashed var(--ox-border);
  margin: 14px 0 0;
  padding-top: 12px;
}
.ox-card-fix::before {
  content: '→ ';
  color: var(--ox-brand-deep);
  font-weight: 700;
}

/* 概念对比 */
.ox-compare-name {
  font-family: var(--vp-font-family-mono);
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 10px;
}
.ox-compare-hi {
  border-color: var(--ox-brand);
}
.ox-compare-hi .ox-compare-name {
  color: var(--ox-brand-deep);
}
.ox-punch {
  font-size: 16px;
  font-weight: 600;
  text-align: center;
  margin: 36px 0 0;
}
.ox-cap { min-height: 170px; }

/* 架构 */
.ox-arch {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 32px;
  align-items: center;
}
.ox-arch-imgbox {
  display: block;
  border: 1px solid var(--ox-border);
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
}
.ox-arch-img {
  display: block;
  width: 100%;
}
.ox-arch-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.ox-arch-list li strong {
  display: block;
  font-size: 15px;
  margin-bottom: 4px;
}
.ox-arch-list li span {
  font-size: 14px;
  line-height: 1.7;
  color: var(--ox-text-2);
}
.ox-more {
  display: inline-block;
  margin-top: 28px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ox-brand-deep);
  text-decoration: none !important;
}

/* 设计原则 */
.ox-principle {
  border-left: 3px solid var(--ox-brand);
  padding: 4px 0 4px 18px;
}
.ox-principle-title {
  font-size: 16px;
  font-weight: 700;
  margin: 0 0 6px;
}

/* 快速开始 */
.ox-start-grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 24px;
  align-items: stretch;
}
.ox-terminal {
  background: var(--ox-navy);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 24px 48px -24px rgba(12, 20, 36, 0.5);
}
.ox-terminal-bar {
  display: flex;
  gap: 7px;
  padding: 13px 16px;
  background: var(--ox-navy-2);
}
.ox-terminal-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #33415e;
}
.ox-terminal-body {
  margin: 0;
  padding: 20px 22px;
  font-family: var(--vp-font-family-mono);
  font-size: 13.5px;
  line-height: 2.05;
  color: #dbe4f3;
  overflow-x: auto;
}
.ox-terminal-body .ox-p {
  color: var(--ox-brand);
  font-weight: 700;
}
.ox-terminal-body .ox-c {
  color: #64748b;
}
.ox-agent-card {
  display: flex;
  flex-direction: column;
}
.ox-agent-yaml {
  margin: 16px 0 0;
  padding: 14px 16px;
  background: var(--ox-bg-soft);
  border: 1px solid var(--ox-border);
  border-radius: 10px;
  font-family: var(--vp-font-family-mono);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ox-text-2);
  overflow-x: auto;
}

/* 路线图 */
.ox-phase-tag {
  display: inline-block;
  font-size: 12px;
  font-weight: 700;
  color: var(--ox-brand-deep);
  background: var(--ox-glow);
  border-radius: 999px;
  padding: 3px 12px;
}

/* CTA */
.ox-cta {
  padding: 84px 0;
  background: linear-gradient(135deg, #22314f, #0c1424);
}
.ox-cta-box {
  text-align: center;
}
.ox-cta-title {
  font-size: 30px;
  font-weight: 800;
  color: #f8fafc;
  margin: 0 0 12px;
  border: none;
  padding: 0;
}
.ox-cta-desc {
  font-size: 16px;
  color: #94a3b8;
  margin: 0 0 30px;
}

/* 页脚 */
.ox-footer {
  border-top: 1px solid var(--ox-border);
  background: var(--ox-bg);
  padding: 48px 0 28px;
}
.ox-footer-grid {
  display: grid;
  grid-template-columns: 1.5fr 1fr 1fr;
  gap: 32px;
  padding-bottom: 32px;
}
.ox-footer-brand {
  display: flex;
  gap: 14px;
  align-items: center;
}
.ox-footer-logo {
  width: 44px;
  height: 44px;
  border-radius: 12px;
}
.ox-footer-name {
  font-size: 17px;
  font-weight: 800;
  margin: 0;
}
.ox-footer-tagline {
  font-size: 13px;
  color: var(--ox-text-2);
  margin: 2px 0 0;
}
.ox-footer-col-title {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--ox-text-2);
  margin: 0 0 12px;
}
.ox-footer-link {
  display: block;
  font-size: 14px;
  color: var(--ox-text);
  text-decoration: none !important;
  margin-bottom: 8px;
}
.ox-footer-link:hover {
  color: var(--ox-brand-deep);
}
.ox-footer-bottom {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid var(--ox-border);
  padding-top: 20px;
  font-size: 13px;
  color: var(--ox-text-2);
}

/* ---------- 响应式 ---------- */
@media (max-width: 960px) {
  .ox-hero-grid { grid-template-columns: 1fr; }
  .ox-hero-visual { order: -1; }
  .ox-grid-4, .ox-grid-5 { grid-template-columns: repeat(2, 1fr); }
  .ox-grid-3 { grid-template-columns: 1fr; }
  .ox-arch, .ox-start-grid { grid-template-columns: 1fr; }
  .ox-hero-title { font-size: 34px; }
  .ox-footer-grid { grid-template-columns: 1fr; }
  .ox-hero { padding: 56px 0 48px; }
  .ox-section { padding: 56px 0; }
}
@media (max-width: 560px) {
  .ox-grid-4, .ox-grid-5, .ox-grid-2 { grid-template-columns: 1fr; }
}
</style>
