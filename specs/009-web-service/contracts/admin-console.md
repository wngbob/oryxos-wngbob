# 契约：只读管理台（第 26 节）

**Feature**: `specs/009-web-service` | **Date**: 2026-10-19 | **端点契约**: [rest-endpoints.md](./rest-endpoints.md)

管理台**没有自己的后端**——所有数据来自同一批 `/api/v1` 端点（这正是「API 是完备的对外通道」的验证）。本文件定它的静态资源契约、路由契约、页面↔端点映射、以及**只读纪律**。

---

## 1. 技术形态与落点

| 项 | 值 |
|---|---|
| 栈 | Vue 3 + Vite（与上游同款：`vue ^3.5.13` / `vite ^6.0.7` / `@vitejs/plugin-vue ^5.2.1`） |
| 源码目录 | `oryxos-web/src/main/frontend/` |
| 构建产物 | `oryxos-web/src/main/resources/static/admin/`（Vite `build.outDir: '../resources/static/admin'`，`emptyOutDir: true`） |
| 构建方式 | `frontend-maven-plugin` 绑 `generate-resources`（research D1），产物**不入库**（D2） |
| 访问地址 | `http://<host>:8080/admin/` |
| 依赖 | `vue` / `vite` / `@vitejs/plugin-vue`（`node_modules` 与产物均 gitignore） |

---

## 2. 服务端路由契约（`WebConfig`）

| 路径 | 行为 |
|---|---|
| `/` | `302` → `/admin/` |
| `/admin` | `302` → `/admin/` |
| `/admin/` | forward → `classpath:/static/admin/index.html` |
| `/admin/assets/**` | 静态资源，`Cache-Control: max-age=31536000, public, immutable`（内容哈希文件名，可永久缓存） |
| `/admin/**`（其余未命中） | 静态资源；**未命中时回落 `index.html`**（`no-cache` + resourceChain）——保证刷新子路由不 404（FR-016） |
| `/api/v1/**` | **不受回落影响**（路径域天然隔离，`/api/**` 不在任何 `/admin/**` 规则内） |

`index.html` 自身 **`no-cache`**（否则浏览器缓存住旧版本、引用的 asset 哈希对不上）。

---

## 3. 页面 ↔ 端点映射（7 页，全部只读）

| # | 页面 | 路由 | 调用的端点 | 展示 |
|---|---|---|---|---|
| 0 | 概览 | `/admin/overview`（**落地页**） | `GET /api/v1/info` + `GET /api/v1/health` + `GET /api/v1/profiles` + `GET /api/v1/tools` | **运行时状态总览**：Agent 数（可用 / 共）、活跃会话数、已注册工具数与来源构成、运行时长与版本；健康三项（整体 / 数据库 / Provider 配置就绪）；Agent 清单紧凑版 |
| 1 | 会话 | `/admin/sessions` | `GET /api/v1/sessions/{id}` | 输入会话 ID 查询：状态、所属 Agent、渠道、用户、时间戳、最近 ≤100 条消息 |
| 2 | Agent | `/admin/profiles` | `GET /api/v1/profiles` | Agent 列表：名称、描述、provider/model、工具、定时任务、**`valid`/`error`**（坏配置看得见） |
| 3 | 工具 | `/admin/tools` | `GET /api/v1/tools` | 工具列表：名称、描述、来源（`builtin` / `annotation` / `mcp:<server>`）、输入 schema |
| 4 | 定时任务 | `/admin/schedules` | `GET /api/v1/profiles`（取各 Profile 的 `schedules`） | 各 Agent 声明的 cron 任务：任务 id（`<Agent>#<序号>`，与 `AgentScheduler` 同源）、cron、时区、消息、Agent 配置是否有效；**只呈现配置面**，执行历史/下次触发时刻本节无端点、不虚报 |
| 5 | 长期记忆 | `/admin/memory` | `GET /api/v1/memory` | 核心区全文 + 归档区内容 + 「归档区已截断」标记 |
| 6 | 运行状态 | `/admin/status` | `GET /api/v1/health` + `GET /api/v1/info` | 整体状态、DB、各 Provider 配置就绪、版本、Agent 数、活跃会话数、工具数、运行时长 |

> **2026-10-20 修订注（用户请求，当日第三次）**：新增**定时任务页**（导航第 5 项，`/admin/schedules`）。数据源是**既有的** `GET /api/v1/profiles`——定时任务的定义源就是 `AGENT.md` frontmatter 的 `schedules`，`ProfileView` 自交付起就把它带出来了，故本页**没有新增 REST 路径**（软门禁 ① 未触发），端点总数仍是 10。
>
> **渠道列表页同日裁决不加**：用户要求左栏同时加「渠道列表」，但 `notify_channels` 至今**没有查询端点**（第 26 节 10 个端点里没有它，`JpaNotifyChannelRegistry` 只有读方法的 Java 接缝、没有 REST 出口），加页必须新造 `GET /api/v1/notify-channels` 这一对外概念。**用户裁决（2026-10-20）：本轮只加定时任务页**，渠道管理仍挂扩展阶段。届时要加的不只是端点，还有输出纪律——`url` 含 token，必须按 CLI `notify-channel list` 同款脱敏（整串恰为 `${VAR}` 才原样回显，否则回「已配置」；`config` 只回键名）。

> **2026-10-20 修订注（用户请求，同日两次）**：① 先新增**概览页**并放在导航第一项，`/admin/` 的落点由 `/admin/sessions` 改为 `/admin/overview`；初版按「静态快照、无端点」实现。② **同日用户澄清后改为运行时状态总览**——原话：「OVERVIEW 页面不是讲『OryxOS 是什么』，这个在官网上已经说得很清楚，不用在这里重复。OVERVIEW 页面应该是整个系统状态的一个概览，包括：有多少个 agent，多少个 tool，多少个 session 等等。」故概览页现在读取**既有的四个只读端点**（`/info`、`/health`、`/profiles`、`/tools`），**没有新增任何 REST 路径**（软门禁 ① 未触发）；「OryxOS 是什么」的叙述性内容已从该页移除。`AdminConsoleSourceTest.overviewPageIsLandingPage` 钉死两件事：它排在其余五页之前（当日第三次修订加入定时任务页后，判据已同步为**其余六页**）、`App.vue` 已注册、且必须经 `api.js` 读数据（不是退化成静态介绍页）；端点引用由 §4 第 4 条的映射表判卷。

**第 1 页为什么是「按会话 ID 查询」而不是「会话列表」**：本节的 10 端点契约（`specs/000-runtime-kernel/contracts/rest-api.md`）里**没有** `GET /api/v1/sessions`——新增它就是契约外 REST 路径（软门禁 ①）。如实留痕：**上游实现里有这个列表端点**（连同 `/stats`），本仓按既定契约不新增；裁决 ⑧ 已把管理台第 1 页定为「按会话 ID 查询」。已入库的 `.claude/skills/oryxos-admin-ui/SKILL.md` 里写的导航项「会话列表」调的就是这个不存在的端点——照它写会造出一个**契约外端点**。故本节把会话页定义为「按 ID 查询」，并在回修 skill 时同步改正。（*修订注，2026-10-19：本段初稿写「列表端点归 29/30 节」，复核发现无依据——spec「明确不做」只把 Profile 增删改 / 记忆写入 / Agent 目录管理派给 29/30 节，**会话列表端点不在其列**，且上游已有该端点。真实理由是本段现在的表述。*）

> **2026-10-20 修订注（第 27 节）**：上段、§3 表格第 1 行与「端点总数仍是 10」的措辞均已失效——第 27 节按该节课件交付了 `GET /api/v1/sessions`（最近 100 条摘要，`?status=` 可选；过滤作用于该窗口内、非法值 400），管理台第 1 页改为「**列表 + 点一行看详情**」，同时调 `GET /api/v1/sessions` 与 `GET /api/v1/sessions/{id}`；端点总数由 10 变 **11**。第 26 节其余九条端点签名、七项导航与只读纪律均未变。

---

## 4. 只读纪律（机器判卷，FR-013）

**MUST NOT** 出现任何新建 / 编辑 / 删除入口。判卷落在 `AdminConsoleSourceTest`（读源码断言）：

1. 前端源码里**只出现 GET**——不出现 `POST` / `PUT` / `PATCH` / `DELETE` 方法名或对应 fetch 选项；
2. 所有请求路径**以 `/api/v1` 开头**（不硬编码主机名、不出现契约外路径）；
3. 七个页面文件存在（`overview` / `sessions` / `profiles` / `tools` / `schedules` / `memory` / `status`）；
4. 七个页面**各自引用**了 §3 映射表里的端点路径（页面不是空壳）；
5. 不出现写操作动词的按钮文案（「新建」「删除」「编辑」「保存」「提交」）；
6. **（2026-10-20 新增）概览页是落地页且读运行时数据**：`router.js` 里 `'/overview'` 排在其余六个路由之前；`App.vue` 已导入并注册该视图；`OverviewView.vue` 必须经 `../api.js` 读数据（不是静态介绍页）。

**为什么用源码断言而不是浏览器测试**：本节前端是纯展示台，没有前端测试基建（research D3 已决定不挂 `npm test`）。源码断言能抓住「有人加了个删除按钮」这类回归，且零额外依赖、零构建时长——符合「能机器判的绝不留给人」。

---

## 5. 设计语言（FR-014）

设计令牌的**唯一真相源**是 `website/.vitepress/theme/custom.css`（官网首页实际使用的值），**不是**课件里那份过期清单（research D8）。核心值：

```css
:root { --ox-bg:#ffffff; --ox-text:#0f172a; --ox-text-2:#52606d;
        --ox-card:#ffffff; --ox-border:#e6e8eb;
        --ox-brand:#f59e0b; --ox-brand-deep:#d97706; --ox-navy:#0c1424; }
.dark { --ox-bg:#0b1220; --ox-card:#111c33; --ox-border:#1f2c47;
        --ox-text:#e5e7eb; --ox-text-2:#94a3b8; --ox-brand:#fbbf24; }
字体：'Inter','PingFang SC',...（正文）；'JetBrains Mono',...（代码）
```

~~**深色底 + 橙色强调**~~；状态色沿用官网语义（成功/警告/失败）。

> **2026-10-20 修订（用户裁决）**：管理台不再固定「深色底 + 橙色强调」，改为严格跟官网首页的实际外观一致——**默认浅色**（上面的 `:root` 档），系统偏好深色时切到官网的深色档（`prefers-color-scheme`，与 VitePress 的默认跟随系统行为相同）。`index.html` MUST NOT 挂深色主题类。上面两段令牌仍逐字取自 `custom.css`，由 `AdminThemeSourceTest` 逐字比对（改官网不跟这里会红）。

> **2026-10-20 修订注（用户请求）**：管理台的**徽标**逐字节取自 `website/public/images/logo-icon.svg`（侧栏品牌块、概览页 hero、favicon 三处共用；`src/assets/logo-icon.svg` 是它的副本，Vite 按 `base: '/admin/'` 重写进 `/admin/assets/`）。与令牌同一口径的「逐字复制 + 机器比对」——由 `AdminThemeSourceTest.adminLogoMirrorsSiteIcon` 判，**换官网 logo 不跟这里会红**。

这套规范 MUST 固化成项目内可复用 skill —— `.claude/skills/oryxos-admin-ui/SKILL.md`（**已存在，本节回修**：换掉过期令牌、把信封从 `ErrorBody(errorCode/message/timestamp)` 改为 `{code,message,data,timestamp}`、把「会话列表」改为「按会话 ID 查询」、补第 26 节的端点清单）。

---

## 6. 错误与空态

| 情况 | 表现 |
|---|---|
| 端点返回非 200 信封 | 页面上显示 `message`（**不是**白屏、不是 console 静默） |
| 列表为空 | 明确空态文案（「暂无 Agent」「暂无工具」），不是一片空白 |
| 网络不可达 | 显示连接失败提示 |

**MUST NOT** 把失败渲染成空数据——「读不到」与「没有」在运维眼里是两件事。

---

## 7. 开发期代理（不属交付契约，但需一致）

`vite.config.js` 的 dev server 把 `/api` 代理到 `http://localhost:8080`，`base: '/admin/'`。开发时 `npm run dev` 起前端、`oryxos serve` 起后端；生产构建产物由 Maven 打进 fat JAR，同源、无代理。
