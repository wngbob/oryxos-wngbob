# Contract: Provider 全局层配置（`oryxos.providers`）

**来源**: spec.md FR-003/FR-010/FR-014、SC-001；`docs/TechnicalSolution.md` §3.1/§3.2；课件第 16 节「第一步：配置分两层」。**这是部署管理员直接手写的配置面**，键名与语义变更必须走修订程序。

## 1. 两层划分

| 层 | 载体 | 回答的问题 | 内容 |
| --- | --- | --- | --- |
| 全局层 | `application.yaml` | 这个实例**连不连得上**某家厂商 | 有哪些厂商、凭证从哪个环境变量读 |
| Profile 层 | `.oryxos/agents/<name>/AGENT.md` 的 frontmatter | 这个 Agent **怎么用** | 用哪个厂商、哪个模型、什么温度 |

两层的**唯一耦合点**是厂商名：Profile 的 `provider.name` 必须能在全局层清单里找到同名项。

## 2. 全局层格式

```yaml
oryxos:
  providers:
    - name: deepseek            # 厂商名，全局唯一；Profile 按此名引用
      api-key: ${DEEPSEEK_API_KEY}   # MUST 为环境变量占位，禁止明文
```

- `name`：非空字符串，**在清单内 MUST 唯一**。
- `api-key`：MUST 为 `${ENV_VAR}` 形式；运行时从环境变量解析。**MUST NOT** 写明文（章程原则 VIII、FR-010）。

## 3. Profile 层格式（frontmatter 片段）

```yaml
provider:
  name: deepseek          # 必须命中全局层清单
  model: deepseek-chat    # 用哪个模型，Profile 自己定
  temperature: 0.7        # 可选；不写则不显式传，用模型默认
```

完整 frontmatter 字段表见上游契约 `specs/000-runtime-kernel/contracts/agent-directory.md` §2。

## 4. 校验规则与失败行为

| 情形 | 行为 |
| --- | --- |
| 清单内 `name` 重复 | **启动即报错**，指明重复的名字（[research.md](../research.md) D10） |
| `api-key` 的环境变量缺失 | 报错指明**变量名**；MUST NOT 回显任何凭证值 |
| 清单里声明了当前实现不支持的厂商 | 报错指明该名字（本节仅实现 `deepseek`） |
| Profile 的 `provider.name` 不在清单中 | 该 Profile **判为不合法**：记错误日志、跳过该 Agent、**不阻断**其余 Agent 加载 |
| Profile 的 `provider.model` 缺失 | 同上，判为不合法并跳过 |

## 5. 显式映射（本契约的核心约束）

启动时按清单**逐条构造** `Map<String, ChatModel>`，键即厂商名：

```text
oryxos.providers[0].name ──► Map key
        api-key              ──► 该厂商 ChatModel 的构造参数
```

- MUST NOT 通过扫描 Spring 容器中 `ChatModel` 类型的 Bean 来建立这份映射（章程原则 III）——多厂商并存时 Bean 类型相同，类型扫描必有歧义。
- 因此本节**不引入** `spring-ai-starter-model-*`（其自动配置会产出按类型注入的 Bean），而是直接依赖模型实现并自行构造（[research.md](../research.md) D3）。

## 6. 新增一家厂商要改什么（SC-001 的兑现方式）

1. 在 `oryxos.providers` 追加一项（name + api-key 占位）；
2. 在 `ProviderConfiguration` 的构造分支里为该 name 增加一条实现分支；
3. 把对应模型实现依赖加进 `oryxos-provider/pom.xml`。

**Agent 侧零改动**——业务方只需把 `AGENT.md` 的 `provider.name` 改成新名字。第 2、3 步是"接入新厂商"的一次性工程动作，不属于业务方。
