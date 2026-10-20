---
name: oryxos-admin-ui
description: >-
  生成或扩展 OryxOS 的 Web 管理台（web manager）前端——Vue 3 + Vite 单页应用，视觉与官网首页
  （website/，VitePress）完全同源：默认浅色、跟随系统深色偏好、琥珀色点缀、Inter/JetBrains Mono。内置这套设计 token、
  工程约定（base '/admin/'、产物落 oryxos-web static/admin、SPA 路径回落）、组件与三态规范、验收清单。
  当用户要「做/生成管理台前端、加一页 admin 页面（如 Agent 管理页）、让管理台跟首页风格一致、
  扩展 /admin 界面」时使用。仅生成只读或调既有 REST 的前端，不写后端、不自创配色。
argument-hint: "要生成/扩展的页面（如：会话详情查询页 / Agent 管理页），留空则生成整套只读管理台"
---

# OryxOS 管理台前端生成规范

本 skill 是 OryxOS Web 管理台（`/admin`）前端的**唯一风格与工程口径来源**。生成或扩展管理台页面时读它、照它做，保证每次产出跟官网首页同一个气质、可复现、能被 Spring 直接托管。

## 何时用 / 不用

- **用**：生成整套只读管理台（26 节）、给管理台加页（30 节的"Agent 管理"页：列表 + 新建表单 + 编辑/删除）、调整管理台样式使其与首页一致。
- **不用**：后端 REST（那是 spec-kit 的 Java 管道）、认证鉴权（核心阶段内网假设）、脱离下方 token 的自创配色。

## 一、设计 token（唯一风格来源，值取自 `website/.vitepress/theme/custom.css`，一个字别自创）

**默认浅色、跟随系统的深色偏好、琥珀色只做点缀**，跟首页完全一致。生成的 Vue 工程里放一份 `src/styles/tokens.css` 并全局引入。

> **2026-10-19 修订（第 26 节回修）**：本段此前给的是 `--bg/--brand-1/--text-1` 那套**过期清单**（`#000000` 底 + `#f97316` 橙），与官网实际使用的不一致。下面是 `website/.vitepress/theme/custom.css` 的**逐字真值**——官网首页运行的就是这一份，管理台照抄才能"并排看是同一套"。
>
> **2026-10-20 修订（用户裁决）**：管理台**不再要求「深色底 + 橙色强调」**，整个外观严格跟官网首页一致——默认是首页的**浅色档**；系统偏好深色时切到官网的深色档（与 VitePress `appearance` 的默认行为相同）。`index.html` **MUST NOT** 再挂深色主题类；两档由 `prefers-color-scheme` 落地。`AdminThemeSourceTest` 逐字比对下面两段，改官网不跟这里会红。
>
> **2026-10-20 补（用户请求，同日三次）**：**徽标**纳入同源约束——管理台的 `src/assets/logo-icon.svg` 是 `website/public/images/logo-icon.svg` 的**逐字节副本**（侧栏品牌块、favicon 共用），`AdminThemeSourceTest.adminLogoMirrorsSiteIcon` 判字节相等，换官网 logo 不跟这里会红。新增**概览页**并置为导航第一项；初版是静态产品介绍，**同日按用户澄清改为运行时状态总览**（用户原话：「OVERVIEW 页面应该是整个系统状态的一个概览，包括：有多少个 agent，多少个 tool，多少个 session 等等」——「OryxOS 是什么」在官网上讲，不在这里重复）。它读既有四个端点，未新增 REST 路径。同日再新增**定时任务页**（`AGENT.md` 的 `schedules` 经既有 `/profiles` 端点呈现，同样未新增端点）；用户同轮要求的**渠道列表页本轮不做**——`notify_channels` 没有查询端点，加它要新造 REST 路径，经用户裁决改挂扩展阶段。

```css
/* 品牌色（琥珀 × 深海军蓝）——与官网同源 */
:root {
  --vp-c-brand-1: #d97706;
  --vp-c-brand-2: #f59e0b;
  --vp-c-brand-3: #fbbf24;
  --vp-c-brand-soft: rgba(245, 158, 11, 0.16);

  /* 首页设计变量（浅色） */
  --ox-bg: #ffffff;
  --ox-bg-soft: #f7f8fa;
  --ox-card: #ffffff;
  --ox-border: #e6e8eb;
  --ox-text: #0f172a;
  --ox-text-2: #52606d;
  --ox-brand: #f59e0b;
  --ox-brand-deep: #d97706;
  --ox-navy: #0c1424;
  --ox-navy-2: #16213a;
  --ox-glow: rgba(245, 158, 11, 0.12);
}

/* 官网的深色档；管理台在系统偏好深色时以 @media (prefers-color-scheme: dark) 应用这一份 */
.dark {
  --vp-c-brand-1: #fbbf24;
  --vp-c-brand-2: #f59e0b;
  --vp-c-brand-3: #d97706;
  --vp-c-brand-soft: rgba(245, 158, 11, 0.16);

  /* 首页设计变量（深色） */
  --ox-bg: #0b1220;
  --ox-bg-soft: #0e1626;
  --ox-card: #111c33;
  --ox-border: #1f2c47;
  --ox-text: #e5e7eb;
  --ox-text-2: #94a3b8;
  --ox-brand: #fbbf24;
  --ox-brand-deep: #f59e0b;
  --ox-navy: #070d1a;
  --ox-navy-2: #0c1424;
  --ox-glow: rgba(245, 158, 11, 0.08);
}

/* 字体栈（官网真值） */
:root {
  --vp-font-family-base:
    'Inter', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei',
    'Helvetica Neue', Helvetica, Arial, sans-serif;
  --vp-font-family-mono:
    'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}
```

**管理台不固定深色**：`index.html` 的 `<html>` **不带** `class="dark"`；上面 `.dark` 那份值由 `@media (prefers-color-scheme: dark) { :root { … } }` 应用（与官网跟随系统的默认行为一致）。不要另做主题切换开关——那是官网才有的能力，管理台不需要。

**除上面这些之外只允许这几项增量**（管理台有状态语义、有圆角刻度，官网都没有）：状态色浅深两档 `--ox-ok` / `--ox-warn` / `--ox-err`（浅色 `#15803d` / `#b45309` / `#b91c1c`，深色 `#4ade80` / `#fbbf24` / `#f87171`——同一语义色在白底与深底上都要够读），以及三个圆角刻度 `--ox-radius-card: 14px` / `--ox-radius-control: 10px` / `--ox-radius-chip: 8px`（照官网卡片 / 按钮 / 徽标的字面量抽成变量）。**再没有别的色值可以新造。**

- 正文用 `--vp-font-family-base`；**ID / session_id / JSON / cron / token 数这类机读值一律 `--vp-font-family-mono`**。
- 主色只点睛（激活项、链接、数值高亮），不做大色块背景；整体克制、留白足。

## 二、工程约定（决定它能不能被 Spring 托管）

- **技术栈**：Vue 3 + Vite（跟 `website/` 同生态；管理台是 app，用普通 Vue SPA，不用 VitePress）。源码在 `oryxos-web/src/main/frontend/`。
- **base 与产物**：`vite.config` 设 `base: '/admin/'`、`build.outDir` 指到 `../resources/static/admin`（即 `oryxos-web/src/main/resources/static/admin/`），资源用相对路径。
- **SPA 路径回落**：Spring 对 `/admin/**` 未命中路径回落 `admin/index.html`；`GET /api/v1/**` 不受影响（后端接线，提醒 plan 处理）。
- **数据来源**：只调 `/api/v1/**`。只读页调 `GET`（端点清单见下节）；管理页（30 节）调 `POST/PUT/DELETE /agents`。开发期 `npm run dev` 起热更、代理 `/api` 到 `:8080`；发布形态是"打进 fat JAR、一个进程托管一切"。
- **概览页的口径**：它是**运行时状态总览**，不是产品介绍页——产品叙事在官网首页，管理台只回答「底座此刻什么状况」。数字全部取自既有端点（`/info` / `health` / `profiles` / `tools`），**不新增端点**、不做定时轮询（打开时读一次）；四个请求分开报错，一个挂掉不把其余数字一起藏起来。若确实需要一页纯说明性内容，默认不放进管理台。
- **响应信封**：统一是 `{code, message, data, timestamp}`，`code` 的值就是 HTTP 状态码（成功 200）。**没有 `errorCode` 字段**——那是本 skill 早先版本的笔误，26 节已按 `oryxos-web` 的 `ApiResponse` 核实改正。
- **错误展示**：`code !== 200` 时把 `message` 显示出来，不吞、不弹裸堆栈；`code === 200` 时页面用的是 `data`，不是整个信封。

## 二·补、端点清单（26 节交付的 10 个 + 27 节补的 1 个，管理台只能用这些）

| 分组 | 端点 | 管理台用途 |
|---|---|---|
| 会话 | `POST /api/v1/sessions` | 业务系统创建会话（**管理台不调**） |
| 会话 | `GET /api/v1/sessions` | 第 1 页：会话列表（最近 100 条摘要，`?status=` 可选） |
| 会话 | `POST /api/v1/sessions/{id}/messages` | 业务系统发消息（**管理台不调**） |
| 会话 | `GET /api/v1/sessions/{id}` | 第 1 页：点一行查详情与历史 |
| 会话 | `DELETE /api/v1/sessions/{id}` | 归档（**管理台不调**） |
| Agent | `POST /api/v1/agents/{name}/invoke` | 无状态调用（**管理台不调**） |
| 查询 | `GET /api/v1/profiles` | 第 2 页：Agent 列表 |
| 查询 | `GET /api/v1/tools` | 第 3 页：工具注册表 |
| 查询 | `GET /api/v1/memory` | 第 5 页：长期记忆两区 |
| 状态 | `GET /api/v1/health` | 第 6 页：整体状态 + DB + 各 Provider 配置就绪 |
| 状态 | `GET /api/v1/info` | 第 6 页：版本、Agent 数、活跃会话数、工具数、运行时长 |

**没有 `GET /api/v1/sessions`（会话列表端点）**。本节 10 端点契约里不存在它，新增它就是契约外 REST 路径。故第 1 页是「**按会话 ID 查询**」——用户粘贴一个 ID（来自创建会话端点的响应）查详情，页面上**不许**有「会话列表」这个导航项，也不许造一个列表端点。

> 修订注（2026-10-20，第 27 节）：上面这条已被推翻。第 27 节按该节课件交付了 `GET /api/v1/sessions`（最近 100 条摘要，`?status=` 可选），第 1 页（会话）改为「**列表 + 点一行看详情**」，导航七项不变；只读纪律不变（只发 GET、无写入口）。此后引用本节时以修订注为准。

## 三、布局与组件规范

- **骨架**：左侧竖直白色导航（顶部 **官网徽标** `<img src="../assets/logo-icon.svg">` 34px 圆角 + 右侧 "OryxOS" 与药丸形「管理台 · 只读」徽标）+ 右侧内容区；页面底色用 `--ox-bg-soft`、卡片用 `--ox-card`，卡片才浮得起来。
- **页头**：首页同款三级——小号色块字（`SESSIONS` / `AGENTS` …，`--ox-brand-deep` + 字距 3px）→ 28px/800 标题 → 14px `--ox-text-2` 说明。
- **导航项（只读版七项）**：**概览**（`/overview`，**落地页**，读 `/info` + `/health` + `/profiles` + `/tools` 四个既有端点）、会话、Agent、工具、**定时任务**（`/schedules`，读 `/profiles` 的 `schedules`）、长期记忆、运行状态。**渠道列表不在这套导航里**：`notify_channels` 没有 REST 端点（2026-10-20 用户裁决改挂扩展阶段），加它必须先补端点与脱敏纪律。（会话页自第 27 节起是**列表 + 点一行看详情**——见上节的修订注）激活项用「左 3px 品牌色描边 + 柔光底 + 品牌色文字」（首页 `ox-principle` 的手法）。
- **概览页（运行时总览）**：顶部四项计数卡（Agent / 活跃会话 / 已注册工具 / 运行时长）→ 健康检查卡（整体 / 数据库 / Provider 配置就绪）→ 两栏：Agent 清单紧凑版（名称 / 厂商模型 / 有效无效）与工具构成（按来源计数）→ 一行脚注说明会话明细按 ID 查。计数卡照首页数字卡的节奏：小号色标 → 30px 等宽数字 → 一行说明；四栏在 1100px 折两栏、760px 折单栏。**不重复「OryxOS 是什么」**——那是官网首页的职责。
- **表格**：白底卡片、行分隔 `--ox-border`、表头压在 `--ox-bg-soft` 上、行悬停同色；机读列等宽字体；长文本可折叠。
- **按钮**：`.btn-primary`（实心品牌色 + 深字 + 柔光投影）与 `.btn-ghost`（白底描边）两档，照首页 `.ox-btn`；输入框用 `.field`（白底、10px 圆角、聚焦品牌描边 + 柔光）。
- **状态**：小标签——成功 `--ox-ok`、失败 `--ox-err`、警告 `--ox-warn`。
- **三态必备**（契约 `contracts/admin-console.md` §6）：

  | 状态 | 表现 | 禁止 |
  |---|---|---|
  | 加载中 | 明确的「正在读取…」占位 | 白屏 / 无反馈 |
  | 空数据 | 明确空态文案（「这个会话还没有消息」「暂无工具」） | 一片空白 |
  | 错误 | 显示信封 `message`（如「查询失败：session not found: x」） | 把失败渲染成空数据 |

  **「读不到」与「没有」在运维眼里是两件事**，MUST NOT 混成同一个画面。

- **响应式**：窄屏导航收起为顶部抽屉；表格横向可滚动，不撑破布局。

## 四、生成 / 扩展流程

1. 读本 skill 的 token 与约定；若 `oryxos-web/src/main/frontend/` 已存在，先看它现有结构、复用 `tokens.css` 与组件，不另起一套。
2. 按需求生成/新增页面（组件 + 路由 + 调用的 REST 端点）。加页时（如 30 节 Agent 管理）：列表用 `GET`，新建/编辑用表单调 `POST/PUT`，删除二次确认调 `DELETE`，全部复用同一套 token 与表格/表单组件。
3. 确保 `vite.config` 的 `base`/`outDir` 正确、`npm run build` 产物落在 `static/admin/`。
4. 过一遍验收清单。

## 五、验收清单（做完自查）

- 配色/字体/圆角全部来自上面的 token，无自创色值；主色只用于强调（标签、链接、激活项、数值），不铺大面积背景。
- `index.html` 不挂深色主题类；浅色 `:root` 与深色档逐字等于 `website/.vitepress/theme/custom.css`（`AdminThemeSourceTest` 机器判卷，改官网不跟这里会红）。
- 徽标 `src/assets/logo-icon.svg` 逐字节等于 `website/public/images/logo-icon.svg`，且文件 `/index.html` 的 favicon 指向它（`AdminThemeSourceTest.adminLogoMirrorsSiteIcon` 机器判卷）。
- 概览页排在导航第一项、经 `api.js` 读取运行时状态（`AdminConsoleSourceTest.overviewPageIsLandingPage` 机器判卷），且它引用的四个端点与契约 §3 映射表一致；概览页不出现产品介绍式长文。
- 定时任务页只呈现**配置面**（`AGENT.md` 的 `schedules`，经既有 `/profiles` 端点），不虚报执行历史与下次触发时刻——那两张表（`scheduled_tasks` / `task_executions`）尚未创建、也没有端点。
- 每页三态（加载/空/错）都有，无白屏；错误展示信封 `message`，且**不把失败渲染成空数据**。
- 只调 `/api/v1/**` 且只发 `GET`；只读页无写操作入口——**源码里不出现「新建」「删除」「编辑」「保存」「提交」**（26 节，由 `AdminConsoleSourceTest` 机器判卷）；管理页写操作有二次确认（30 节）。
- `npm run build` 产物在 `oryxos-web/src/main/resources/static/admin/`，`base: '/admin/'`，刷新子路由不 404（依赖 Spring 对 `/admin/**` 回落 `index.html`；`/admin/` 本身由视图控制器 forward 到 `index.html`）。
- 窄屏可用；视觉跟官网首页并排看是同一套设计语言（默认浅色、琥珀点缀、Inter/JetBrains Mono）。

> 不要为了"更漂亮"引入外部 UI skill 或大型组件库来盖过这套 token——一致性 > 花哨。确需组件库时选无主题/可完全被 token 覆盖的（headless）。
