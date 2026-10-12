# AgentScope 多 Agent 群聊模拟系统

用 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 写的"微信群"式多 Agent 群聊：
群里是 **1 个真人（你）+ N 个不同人设的 AI**，每个 AI 独立思考、自己决定要不要接话，
接不上话就自然冷场，抢话时会有人"手慢没抢到"。SSE 实时推送，刷新不丢历史。

- 🗂 **多群聊**：左侧会话列表，随时"发起群聊"并勾选把哪几个 agent 拉进来；每个群有独立的成员、聊天记录、群公告、停止开关
- 🧠 **两层长期记忆**：群共识（本群发生过什么）+ 个人印象（"我觉得你上次说话有点冲"），带 RAG 召回 + rerank 精排
- 🎭 **人设即真源**：昵称/头像/人设/啰嗦程度/emoji 倾向/回应速度/群内关系全部存在数据库里，管理台改完立刻生效，不用重启
- ⚙️ **零门槛配置**：首次启动弹窗（或双击 `start.bat`）只填「接口地址 / 密钥 / 模型名」四组，每项都标了中文含义；管理台「模型配置」页随时可改，**保存即生效、不用重启**
- 🔍 **决策透视**：任何一条消息都能展开看"谁想说话、表达欲几分、谁被配额刷掉、为什么沉默"——调真实感全靠它
- 💰 **Token 账本**：门控 / 发言 / 记忆 / 向量调用逐条记账，按群、按成员、按用途、按天汇总
- 📦 **零外部依赖**：单模块 Spring Boot + 单文件 SQLite（WAL），没有 Redis、没有前端工程、没有 Node 构建

> 技术栈：Spring Boot 3.5（WebMVC + SSE，**不用 WebFlux**）、JDK 17、Maven、SQLite + sqlite-vec（vec0 KNN，带纯 Java 余弦兜底）、AgentScope-Java 2.0.4。

---

## 目录

1. [快速开始](#1-快速开始)
2. [使用指南](#2-使用指南)
3. [核心机制](#3-核心机制)
4. [配置项](#4-配置项)
5. [架构](#5-架构)
6. [接口](#6-接口)
7. [测试](#7-测试)
8. [备份与排错](#8-备份与排错)

---

## 1. 快速开始

### 1.1 环境要求

| 项 | 要求 |
|---|---|
| JDK | **17**（必须） |
| Maven | 3.8+ |
| 操作系统 | Windows / macOS / Linux（sqlite-vec 原生库目前只内置了 Windows x86_64，其他平台自动走纯 Java 兜底） |
| 模型服务 | 任意 OpenAI 兼容的 `/chat/completions` 服务（OpenAI / DeepSeek / 阿里云百炼 / 本地 Ollama 都可以） |

> **不想碰配置文件？** Windows 用户直接双击根目录的 `start.bat`：首次运行会弹一个窗口，
> 按中文提示填 4 组「接口地址 / 密钥 / 模型名」，保存后自动启动。
> 其余平台、或想自己掌控的用户看下面的手填方式——两种方式写的是同一个 `.env`，可以混用。

### 1.2 配置

人设、聊天记录都在数据库里，**只有"模型凭据 / 端点 / 向量维度"放在 `.env`**。
`application.yml` 只保留结构与默认值，不用动它。

#### 方式一：双击 `start.bat`（Windows，推荐）

```
双击 start.bat
        ↓
检测到「没有 .env，或里面还没配好主力发言模型」→ 弹出配置窗口
        ↓
选一个「常用服务商」→ 接口地址、模型名自动填好，你只需粘 API Key
        ↓
点「保存并启动」→ 写出 .env → 自动启动 → 自动打开浏览器
```

窗口里只有四组参数，每组三个输入框，每项都标了中文含义：

| 参数 | 中文含义 | 要不要填 |
|---|---|---|
| **主力发言模型 chat** | agent「说什么」用的模型，每条正式发言都调用它，用质量好一点的 | **必填** |
| **门控模型 gate** | 判断每个 agent「要不要接话」，**调用量最大的就是它**，建议用最便宜的 flash 级小模型 | 留空 = 复用主力模型 |
| **向量模型 embedding** | 把消息和记忆转成向量，长期记忆靠它做语义检索 | 留空 = 记忆退化为"最近若干条" |
| **重排模型 rerank** | 对召回的候选做精排 | 留空 = 直接按向量相似度排序 |

外加一个「向量维度」（必须与向量模型输出维度一致，窗口里附了常见值对照表）。

以后再想改参数：双击 `start.bat` 不会再弹窗（`.env` 里已配好主力模型），直接启动；
改参数请用**管理台 → 模型配置**（见 [2.5](#25-管理台--模型配置)），保存即生效。

#### 方式二：手动填 `.env`（macOS / Linux，或想自己掌控）

```bash
cp .env.example .env        # Windows 用 copy .env.example .env
```

`.env` 已被 `.gitignore` 忽略，不会被提交；仓库里只有占位符版本的 `.env.example`。

##### 常用服务商该填什么（照抄即可，Key 要自己去控制台建）

| 服务商 | baseUrl | 主力模型 chat | 向量模型 | 向量维度 |
|---|---|---|---|---|
| 阿里云百炼 / 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `text-embedding-v4` | 1024 |
| DeepSeek 官方 | `https://api.deepseek.com/v1` | `deepseek-chat` | 无 | 1024 |
| 硅基流动 SiliconFlow | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V3` | `BAAI/bge-m3` | 1024 |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` | `embedding-3` | 2048 |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` | `text-embedding-3-small` | 1536 |
| 本地 Ollama | `http://localhost:11434/v1` | `qwen2.5:7b` | `nomic-embed-text` | 768 |

- **Key 在哪拿**：登录各家控制台 → 「API-KEY / 密钥管理」→ 新建（一般以 `sk-` 开头）。Ollama 不需要 Key。
- **带"思考模式"的网关**（阿里云百炼 / MaaS 上的 `qwen3.x`、`deepseek` 等）要把「关闭思考模式」勾上；
  DeepSeek 官方 / OpenAI / 硅基流动 / 智谱**不要勾**（它们的网关不认 `enable_thinking` 这个非标准字段，会报 400）。
  这个开关在管理台是「进阶」里的一个勾选框，对应 `app.model.*.disable-thinking`。
- 模型名以你控制台里显示的为准，上表只是常见默认值。`baseUrl` 填到 `/v1` 为止，**不要**带 `/chat/completions`。

##### 最小配置（3 行，能跑起来的最少配置）

只填**主力发言模型**即可。门控、记忆、rerank 都会自动降级：

```properties
APP_MODEL_CHAT_BASE_URL=https://api.deepseek.com/v1
APP_MODEL_CHAT_API_KEY=sk-xxxxxx
APP_MODEL_CHAT_MODEL=deepseek-chat
```

未配置的部分会这样降级，不影响启动：

| 没配置 | 后果 |
|---|---|
| gate（门控模型） | 自动用 chat 模型做门控（贵一点，功能不变） |
| embedding | 记忆召回退化为"最近若干条"，不再做语义检索 |
| rerank | 跳过精排，直接用向量相似度排序 |

##### 完整配置（推荐：门控用便宜模型，省钱）

```properties
# 运行环境
APP_DB_PATH=data/chat.db          # SQLite 文件位置，自动创建目录
APP_ACCESS_TOKEN=                 # 留空=不校验；公网暴露时建议设一个
APP_VEC_DIMENSION=1024            # 必须与 embedding 模型输出维度一致

# 主力发言模型（写消息用）
APP_MODEL_CHAT_BASE_URL=https://api.deepseek.com/v1
APP_MODEL_CHAT_API_KEY=sk-...
APP_MODEL_CHAT_MODEL=deepseek-chat

# 门控模型（flash 级小模型，判断"要不要接话"，调用量最大，用便宜的）
APP_MODEL_GATE_BASE_URL=https://api.deepseek.com/v1
APP_MODEL_GATE_API_KEY=sk-...
APP_MODEL_GATE_MODEL=deepseek-chat

# embedding（记忆检索用）
APP_MODEL_EMBEDDING_BASE_URL=https://api.openai.com/v1
APP_MODEL_EMBEDDING_API_KEY=sk-...
APP_MODEL_EMBEDDING_MODEL=text-embedding-3-small
# ⚠️ 换成 text-embedding-3-small 时记得把 APP_VEC_DIMENSION 改成 1536

# rerank（可选；没统一协议，要填完整 URL）
APP_MODEL_RERANK_BASE_URL=
APP_MODEL_RERANK_API_KEY=
APP_MODEL_RERANK_MODEL=
```

##### 阿里云百炼 / MaaS 现成示例（已实测跑通）

```properties
APP_VEC_DIMENSION=1024

APP_MODEL_CHAT_BASE_URL=https://<你的实例>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
APP_MODEL_CHAT_API_KEY=sk-...
APP_MODEL_CHAT_MODEL=deepseek-v4.1-flash

APP_MODEL_GATE_BASE_URL=https://<你的实例>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
APP_MODEL_GATE_API_KEY=sk-...
APP_MODEL_GATE_MODEL=qwen3.8-flash

APP_MODEL_EMBEDDING_BASE_URL=https://<你的实例>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
APP_MODEL_EMBEDDING_API_KEY=sk-...
APP_MODEL_EMBEDDING_MODEL=qwen3.7-text-embedding

APP_MODEL_RERANK_BASE_URL=https://<你的实例>.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
APP_MODEL_RERANK_API_KEY=sk-...
APP_MODEL_RERANK_MODEL=qwen3.7-text-rerank
```

> ⚠️ 百炼的 rerank 要填**完整端点**（含 `.../text-rerank/text-rerank`），不是 `/v1`。
> 其他厂商的 rerank 直接填它们的 rerank 地址即可：Cohere `https://api.cohere.com/v2/rerank`、Jina `https://api.jina.ai/v1/rerank`。
> `disable-thinking`、级联深度、配额等**应用级开关**仍在 `application.yml` 里（管理台只管模型相关的几项），见 [4. 配置项](#4-配置项)。
> 判断"今天上不上班"会查一次外部节假日接口（默认 `api.apisbo.com`，一天只查一次），所以国庆/春节/调休都能判准；
> 查不到会自动退回"周一~周五=工作日"。不想让本机联网查，设 `APP_HOLIDAY_ENABLED=false`。

**`.env` 的解析规则**（由 `config.DotenvEnvironmentPostProcessor` 负责）：

- 从**当前工作目录向上最多 5 层**查找 `.env`，所以"工程根目录 `mvn spring-boot:run`"和"在 `target/` 里跑 jar"都能找到同一个文件；也可以用 `-Ddotenv.location=/path/to/.env` 显式指定
- 支持 `KEY=value`、`KEY="value"`、`KEY='value'`、`export KEY=value`、`KEY=value  # 行尾注释`
- 优先级：**系统环境变量 > `.env` > `application.yml` 默认值**。所以容器/CI 里不需要 `.env` 文件，直接传环境变量即可覆盖：
  ```bash
  APP_DB_PATH=/data/chat.db APP_MODEL_CHAT_API_KEY=sk-xxx java -jar app.jar
  ```
- 管理台「模型配置」与首启向导写回的就是**同一个文件**：只就地替换受管的那几行，
  你手写的注释和自定义变量都会原样保留（不会整文件覆盖）
- 正因为环境变量优先级更高：**如果某个 `APP_MODEL_*` 是用系统环境变量设的**，
  管理台会黄色告警把那个键列出来——那时写 `.env` 重启后也会被顶掉，要改请改系统环境变量

### 1.3 启动

| 你的情况 | 怎么启动 |
|---|---|
| Windows，想省事 | 双击 `start.bat`（首次会弹窗配模型，之后自动启动并打开浏览器） |
| 装了 Maven | `mvn spring-boot:run` |
| 用 IDEA | 直接运行主类 `com.example.agentscopegroupchat.GroupChatApplication` |
| 只要一个 jar | `mvn clean package` → `java -jar target/agentscope-group-chat-1.0.0.jar`（`.env` 放 jar 同目录） |

> `start.bat` 会优先用 `target/` 下编译好的 jar，没有 jar 就自动改用 `mvn spring-boot:run`。
> 除 IDEA 那条路，其余方式都要求机器上有 **JDK 17**（Maven 方式还需要 Maven）。

### 1.4 访问

| 页面 | 地址 | 说明 |
|---|---|---|
| 群聊页 | http://localhost:8080/ | 左侧会话列表 + 右侧聊天区 |
| 管理台 | http://localhost:8080/admin.html | Agent 管理 / 群聊管理 / Token 统计 / **模型配置** |
| 直达模型配置 | http://localhost:8080/admin.html#model | 群聊页提示"模型未配置"时点它跳到这里 |

> 如果还没配模型，群聊页顶部会有一条横幅，点一下直接跳到「模型配置」——
> **填完保存立即生效，不用重启**。

### 1.5 首次启动会发生什么

1. 建表（幂等；老库只补缺的列，不会动已有数据）
2. `agents` 表为空时，种子导入 **5 个示例人设**：
   🎭 热心大哥（话痨、爱张罗）、😏 毒舌老王（高冷、一针见血）、🌸 小元气（元气少女、爱用颜文字）、
   🤓 较真张工（理工男、爱纠错）、🐟 潜水员阿呆（惜字如金、爱发冷幽默）
3. 创建**示例群**「摸鱼搭子群」（`app.chat.group-id`，默认 `default`，带 `is_sample` 标记、不允许解散），
   并把 5 个人设全部拉进来——直接打开群聊页就能看他们怎么聊

> 🆕 多群改造之前的老数据库可以直接升级：启动时会自动给 `token_usage` 补 `group_id` 列，
> 并把历史 token 流水回填到示例群，老消息、老记忆一条不动。

---

## 2. 使用指南

### 2.1 群聊页

**左侧会话列表**：每个群显示头像、群名、最后一条消息、时间、"示例"和"已停止"标记、成员数，
按最近活跃排序（没人说话的群自动沉底）。

| 你想做的事 | 怎么做 |
|---|---|
| 进某个群 | 点会话列表里的群 |
| 新建群聊 | 点「＋ 发起群聊」→ 填群名/头像 → **勾选要把哪几个 agent 拉进来** → 「创建并进入」。被拉进来的人会立刻打个招呼 |
| 看/改群资料 | 点右上角 👥 打开信息抽屉：改名、换头像、改群公告（群公告会写进所有人的视野）、保存 |
| 拉人进群 | 信息抽屉底部「拉人进群」勾选后确认（同样会触发打招呼） |
| 把人移出群 | 信息抽屉里点成员后面的「移出群」。**只是不在这个群说话了**，他的个人印象、其他群完全不受影响 |
| 解散群 | 信息抽屉底部「解散本群」（示例群不可解散）。会连同该群的消息、群共识、token 流水一起删除 |
| 让全群安静一会儿 | 右上角 ⏸ 停止聊天：消息照常落库，但**不触发任何 agent 决策**。再点一下恢复 |
| @ 某个人 | 输入框里打 `@`，下方弹出成员快捷栏，点一下就插入 `@昵称`。被 @ 的人**强制进入候选**（仍有 5% 概率"装没看见"） |
| 知道 AI 在干嘛（不想以为卡住） | 群名下方实时显示进度：`… 正在看这条消息…`（全员门控中）→ `… 正在想怎么接话…`（轮到它、拟人延迟中）→ `… 正在输入…`（正在生成）。被 @ 却没回时会飘一条提示，如「小元气 好像没理睬你 🙈」；真人发话却全员没接，会提示「这条消息暂时没人接话 —— 试试直接 @ 某个人点名」 |
| 看 AI 为什么说话/不说话 | 鼠标移到 AI 消息上 → 「决策透视」，看每个人的 想不想说 / 表达欲几分 / 谁被配额刷掉 / 沉默原因 |
| 撤回自己的消息 | 鼠标移到自己的消息上 → 「撤回」。AI 视野里该消息会变成"已撤回" |
| 翻更早的消息 | 往上滚，或点列表顶部的提示 |
| 看这个群花了多少钱 | 输入框上方「本群 tokens：N」实时累计 |

破折号、颜文字、跨行、emoji 都支持；`Enter` 发送，`Shift+Enter` 换行。

### 2.2 管理台 · Agent 管理

人设的**唯一真源**是数据库，这个 Tab 就是它的编辑器。

- **新增 / 编辑**：昵称、头像（emoji）、人设 prompt、啰嗦程度、emoji 倾向、回应速度、表达欲基准、启用状态
- **群内关系**：不用手写 JSON —— 用"添加一位成员"的可视化行编辑器填 **熟悉度 / 好感 / 内部梗**，
  保存时自动拼成存储格式；想手改 JSON 就点右上角「高级」切换
- **停用**是逻辑删除：历史消息和个人印象都保留，只是不再参与发言；可以随时「启用」回来
- **↻ 热重载人设**：改了直接生效，**不用重启**（下次决策就用新配置）

> 新建的人设**不会自动进群**。人设是全局共享的，进哪个群由"拉人"决定——
> 同一个 agent 可以同时待在多个群里。

### 2.3 管理台 · 群聊管理

不看聊天也能管群：

- **＋ 新建群聊**：同一个弹层，填群名/群公告 + 勾选成员
- **信息 / 成员**：改群资料 + 差量同步成员（新勾选的会被拉进来打招呼，取消勾选的会被移出）
- **停止聊天 / 恢复聊天**：和群聊页的 ⏸ 等效
- **解散**：带二次确认（提示会删掉消息/群共识/记忆进度/token 流水），示例群不显示此入口
- **进群**：新标签页直接打开该群

### 2.4 管理台 · Token 统计

- **总览**：累计 tokens、今日 tokens、累计输入/输出，下面是「按群聊」的横向条形图
- **下钻**：点「按群聊」里的任意一行 → 进入该群视图，看**这个群里每个成员**各花了多少
  （含一行「系统（记忆 / 向量）」，那是没有归属 agent 的 embedding / 记忆提取调用）
- **按用途**：门控 GATE / 发言 SPEAK / 记忆 MEMORY / 向量 EMBED 的占比
- 点「← 返回全部群聊」回到总览

### 2.5 管理台 · 模型配置

这个 Tab 改的就是 [1.2 配置](#12-配置) 里那几项模型参数——**保存即生效，不用重启**（唯一例外是「向量维度」，见下）。

- **不想手抄参数**：顶上选一个「常用服务商」，接口地址、模型名、向量维度会自动填好，你只补 API Key
- **四组参数**：主力发言 / 门控 / 向量 / 重排，每组都是「接口地址 + API Key + 模型名」，每项都标了中文含义与建议
- **API Key 不回显明文**：页面只显示 `sk-1****abcd` 这样的掩码。**留空 = 不修改**；要换就粘新的，要清空就点该组的「清除该组 Key」
- **只提交你改过的项**：没动的参数不会被覆盖
- **保存后立即重建模型客户端**：正在聊的那个群，下一条消息就用新模型
- **顶部状态区**会告诉你：配置文件在哪、四组参数当前的生效状态、有没有被系统环境变量占住的键、以及需要重启的提醒

⚠️ 两个容易踩的坑：

1. **API Key 留空 ≠ 清空**。想清空请点「清除该组 Key」；留空只是"保持原样"。
2. **改了「向量维度」必须重启**，而且如果库里已经有旧维度的记忆向量，新旧维度不兼容，建议清空记忆表后重新积累
   （vec0 虚拟表的维度在建库时就固定了，运行期改不了）。

> 这个页面同时是"首启向导"的兜底：就算你没跑 `start.bat`，只要应用能启动（**没配模型也能启动**），
> 打开 `http://localhost:8080/admin.html#model` 就能把参数配上。

### 2.6 把"真实感"调出来

| 想要的效果 | 改哪里 |
|---|---|
| 更爱说话 / 更冷淡 | 人设的「表达欲基准」+ `app.chat.speak-quota-percent`（默认 50%，即单轮最多一半人开口） |
| 打字更快 / 更慢 | 人设的「回应速度」+ `app.chat.typing-delay-min-ms/max-ms`（默认 1.5~4 秒） |
| 话更多 / 更短 | 人设的「啰嗦程度」+ `app.chat.speak-max-tokens`（默认 512） |
| 更爱用 emoji | 人设的「emoji 倾向」 |
| 互相拉帮结派、互相调侃 | 人设的「群内关系」（熟悉度 / 好感 / 内部梗）——**这是"像真群"的灵魂，建议先调这个** |
| 冷场时有人主动起话题 | `app.chat.idle-topic-trigger-sec`（默认 60 秒） |
| 没人回就别浪费 token | `app.chat.max-idle-rounds-without-human`（默认 3，详见 [3.3](#33-冷场开题与沉默节流)） |
| 别刷屏 | `app.chat.cascade-max-depth`（默认 8）：一次发言最多引发多少层级联 |

---

## 3. 核心机制

### 3.1 一轮群聊是怎么发生的

```
有人说了句话（真人 / AI）
        ↓
【全员自决门控】所有成员同时、各自独立地判断"我想不想接话"
   输入：人设卡 + 最近 K 条群消息 + 新消息 + 召回的记忆
   输出（严格 JSON）：{ 想不想说, 表达欲 0~10, 一句话概括 }
   降级：装没看见概率 / 级联深度到顶 / 群被停止 → 直接不参与
        ↓
【硬规则覆盖】被 @ 的人强制进候选（除非撞上 5% 的"装没看见"）
        ↓
【配额裁决】想说话的人 ≤ 半数 → 全放行
          想说话的人 > 半数 → 按表达欲排序取前一半，
                              分数接近（差 ≤1）时随机洗牌 —— 模拟"手慢没抢到"
        ↓
【节奏队列】入选的人用主力模型写正式发言，串行发射，每句之前先"正在输入…"
        ↓
【级联】每句 AI 发言又触发一轮门控，深度 +1 → 直到没人想说 / 到达深度上限 / 真人打断
```

几个刻意的设计：

- **50% 是上限不是下限**：没人接话就自然冷场，绝不强行凑数
- **门控用便宜模型、发言用主力模型**：全员自决的成本 ≈ N 次极廉价调用 + 最多一半的正式生成
- **无状态投喂**：每次只把最近 K 条（默认 30）消息塞进 prompt，不让记忆无限膨胀
- **注入真实时间**：每个 prompt 都带一行 `【当前时间】2026年10月12日 星期一 14:43（下午，工作日）`，
  聊天记录只在跨天 / 断档 >30 分钟处插 `[今天 14:43]` 这样的时间标签。模型不知道"现在几点、周几"时，
  会按语料习惯瞎猜——实测出现过下午两点却感叹"大晚上的"
- **节假日/调休也判准**：只按星期几会把国庆、春节和"调休上班的周六"全判错，于是会查一次外部日历接口
  （默认 `api.apisbo.com`，一天只查一次），把"工作日 / 周末 / 国庆节假期 / 调休上班"写进那句当前时间；
  接口不通就自动退回"周一~周五=工作日"

### 3.2 打断与并发

- **真人插话 = 打断**：队列里还没发出去的排队发言全部作废，基于新消息重新决策（"我们正聊着，你插一句，大家回头理你"）
- **每个群一套独立的运行期状态**（代际号 / 冷场计数 / 轮次线程）：A 群你插话只作废 A 群排队，
  A 群在"打字延迟"里打盹的时候不会堵住 B 群
- agent 跑在独立的有界线程池里，不占用 Tomcat 请求线程；SSE 断线重连用 `Last-Event-ID` 补拉

### 3.3 冷场开题与沉默节流

- **冷场开题**：群里连续 `idle-topic-trigger-sec` 秒无人说话，由"话痨型"人设主动抛个话题
- **沉默节流**：如果连续 `max-idle-rounds-without-human` 轮（默认 3）自发开题都**没有真人接话**，
  就自动暂停自发开题并公告一次——没人看的时候不烧 token。真人一发消息立刻恢复计数
  - 设为 `0` = 不限轮数
  - 只统计"冷场开题"这条链路；真人发言、拉人进群打招呼都不计数

### 3.4 长期记忆：RAG + rerank

**两层记忆，单库分表：**

| 层 | 内容 | 归属 |
|---|---|---|
| 群共识 `shared_facts` | 这个群里发生过什么、达成了什么共识、有什么梗 | **按群隔离** |
| 个人印象 `agent_impressions` | "我觉得你上次说话有点冲" | **按 agent，跨群共享** |

**写入**（后台总结，不实时）：累计 N 条消息（默认 50）或群空闲 5 分钟触发一次，
**一次调用同时产出两层**（省钱）；去重后写库，并写入 vec0 虚拟表。

**召回**（agent 准备正式发言时）：`新消息 + 最近上下文` 作为 query →
embedding 检索（群层 + 该 agent 的个人层）top-10 → rerank 取 top-3 → 注入发言 prompt（标注为
"过去的印象/群共识，可自然引用，但别当成现在正在聊的内容"，避免把旧记忆错当成新话题）。
rerank 没配置就跳过，直接用向量分数排序。

**向量存储**：sqlite-vec 的 vec0 虚拟表做 KNN，KNN 结果与"纯 Java 余弦暴力查"两条路径共用同一份 float32 BLOB，
**原生扩展加载失败会自动降级**，功能完全一致、只是稍慢。

### 3.5 多群：什么共享、什么隔离

| 数据 | 归属 | 说明 |
|---|---|---|
| 人设 `agents` | **全局共享** | 同一个 agent 可以同时待在多个群里，改一次人设所有群立即生效 |
| 个人印象 `agent_impressions` | **跨群共享** | 同一个人设在两个群里，本来就是同一个"人" |
| 群共识 `shared_facts` | 按群隔离 | 每个群有自己的共同记忆 |
| 聊天记录 `messages` | 按群隔离 | |
| 停止开关 `chat_state` | 按群隔离 | 每个群各停各的 |
| 记忆提取进度 `memory_state` | 按群隔离 | |
| token 流水 `token_usage` | 按群 | 支撑按群统计与下钻 |
| 运行期状态（冷场计数 / 队列 / 代际号） | 按群隔离 | 互不干扰 |

**解散群聊**会删除该群的消息、群共识、记忆进度、停止开关与 token 流水（这样统计里不会出现查不到名字的孤儿行）；
`is_sample=1` 的示例群不允许解散。

**SSE 是所有群共用一条流**，每个事件体里都带 `groupId`，前端按当前群过滤（本地单机场景下最简，
断线补拉机制也不用动）。

### 3.6 成本参考

5 个 agent 的群，每来一条真人消息 ≈ 5 次门控（flash 级，极便宜）+ 0~3 次正式生成。
用便宜的 gate 模型，日常玩一天大概几毛到几块钱。Token 统计页可以随时看每个群、每个人花了多少。

---

## 4. 配置项

`application.yml` 只保留结构与默认值。带 🔑 的项由 `.env` 注入（不写就用默认值）；
其中带 ⚙️ 的（四组模型配置 + 向量维度）还可以直接在**管理台 → 模型配置**里改，**保存即写回 `.env` 并热更新**。

| 配置 | 来源 | 默认 | 说明 |
|---|---|---|---|
| `app.db-path` | 🔑 `APP_DB_PATH` | `data/chat.db` | SQLite 单文件路径（自动建目录，WAL 模式） |
| `app.access-token` | 🔑 `APP_ACCESS_TOKEN` | 空 | 留空=不校验；设置后 `/api/**` 需带 `X-Access-Token` 头，SSE 用 `?token=` |
| `app.chat.group-id` / `group-name` | yml | `default` / 摸鱼搭子群 | **示例群**的 id 与名称，启动时自动创建并把所有人设拉进来 |
| `app.chat.cascade-max-depth` | yml | 8 | 级联深度上限 |
| `app.chat.speak-quota-percent` | yml | 50 | 单轮最多多少比例的 agent 能发言 |
| `app.chat.context-window-k` | yml | 30 | 每次投喂最近 K 条消息 |
| `app.chat.typing-delay-min-ms` / `max-ms` | yml | 1500 / 4000 | 拟人打字延迟区间 |
| `app.chat.idle-topic-trigger-sec` | yml | 60 | 冷场多少秒后主动开题 |
| `app.chat.max-idle-rounds-without-human` | yml | 3 | 连续 N 轮自发开题无人应答后停自动聊（`0`=不限） |
| `app.chat.mention-ignore-probability` | yml | 0.05 | 被 @ 时"装没看见"的概率 |
| `app.chat.speak-max-tokens` | yml | 512 | 单条 agent 发言的 max_tokens |
| `app.chat.gate-max-tokens` | yml | 512 | 门控 JSON 的 max_tokens（给小了 JSON 会被截断） |
| `app.holiday.enabled` | 🔑 `APP_HOLIDAY_ENABLED` | true | 是否查外部节假日接口（关掉=只按周一~周五判断） |
| `app.holiday.base-url` | 🔑 `APP_HOLIDAY_BASE_URL` | `https://api.apisbo.com/holidays/date` | 接口前缀，实际请求 `{BASE_URL}/{yyyy-MM-dd}` |
| `app.holiday.timeout-ms` | 🔑 `APP_HOLIDAY_TIMEOUT_MS` | 2500 | 接口超时；超时即降级，不影响群聊 |
| `app.holiday.retry-seconds` | 🔑 `APP_HOLIDAY_RETRY_SECONDS` | 600 | 查询失败后的重试间隔（秒） |
| `app.memory.enabled` | yml | true | 记忆总开关 |
| `app.memory.trigger-msg-count` | yml | 50 | 累计 N 条消息触发一次记忆提取 |
| `app.memory.trigger-idle-sec` | yml | 300 | 或群空闲 N 秒触发提取 |
| `app.memory.recall-top-k` | yml | 10 | 向量召回 top-K |
| `app.memory.rerank-top-k` | yml | 3 | rerank 后注入 prompt 的条数 |
| `app.memory.extract-max-tokens` | yml | 2048 | 记忆提取 JSON 的 max_tokens（条目多时会很长） |
| `app.vec.enabled` | yml | true | 是否尝试启用 sqlite-vec（失败自动降级纯 Java） |
| `app.vec.dimension` | 🔑⚙️ `APP_VEC_DIMENSION` | 1024 | 向量维度，**必须与 embedding 模型一致**；改它需要重启 |
| `app.vec.extension-path` | yml | 空 | 留空=用内置 DLL；填 `none`=强制纯 Java 兜底；也可指定自己的 sqlite-vec 路径 |
| `app.model.chat.base-url` / `api-key` / `model` | 🔑⚙️ `APP_MODEL_CHAT_*` | 空 | 主力发言模型（任意 OpenAI 兼容） |
| `app.model.chat.disable-thinking` | 🔑⚙️ `APP_MODEL_CHAT_DISABLE_THINKING` | true | 在请求体里带 `enable_thinking=false`。思考模式下 `tool_choice=required` 会被网关直接拒（400），而门控/记忆提取依赖它；换成不吃这个参数的厂商可以置 `false`（管理台「进阶」里的勾选框）。gate / embedding / rerank 各有同名的 `disable-thinking`，默认同样是 `true` |
| `app.model.gate.*` | 🔑⚙️ `APP_MODEL_GATE_*` | 空 | 门控模型；三项全空则回退用 chat。**调用量最大，建议用便宜的 flash 级模型** |
| `app.model.embedding.*` | 🔑⚙️ `APP_MODEL_EMBEDDING_*` | 空 | embedding 端点；空则召回退化为"最近若干条"，功能不报错 |
| `app.model.rerank.*` | 🔑⚙️ `APP_MODEL_RERANK_*` | 空 | rerank **完整 URL**；空则跳过 rerank |

任何一项都可以用命令行参数临时覆盖，例如：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.chat.cascade-max-depth=3
```

参数生效优先级：**命令行参数 / 系统环境变量 > `.env` > `application.yml`**。
管理台「模型配置」保存时，会**同时**改运行中的内存值（当前进程立即生效）和 `.env`（重启后依然生效），
两者必须一起做——因为 `.env` 只在启动时被读一次，运行中改文件 Spring 不会重读。

**为什么凭据放 `.env` 而不是 `application.yml`**：yml 要进版本库，密钥写进去一开源就泄露了。
`.env` 已在 `.gitignore` 中，仓库里只提交 `.env.example` 占位符。

关于**结构化输出**（门控判断、记忆提取都要产出严格 JSON）——实现里做了三层兜底，换厂商时不用改代码：
合成 tool + `tool_choice=required`（主路径）→ `response_format: {"type":"json_object"}`（只作用于门控/提取 agent，
不会污染聊天发言）→ 文本里截首尾大括号做宽松解析。

---

## 5. 架构

### 5.1 分层

```
┌──────────────────────────────────────────────────────┐
│ 前端（内嵌静态单页，微信风 UI，消费 SSE）                 │
│   群聊页（会话列表 + 聊天区） | 管理台（4 个 Tab）         │
├──────────────────────────────────────────────────────┤
│ Spring Boot WebMVC                                    │
│   REST API（群/消息/agent/统计/配置/决策）+ SSE /api/stream│
├──────────────────────────────────────────────────────┤
│ 群调度器（自研核心，框架不参与编排）                       │
│   发言门控 / @硬规则 / 50% 配额裁决 / 级联深度             │
│   节奏队列（串行 + 拟人延迟）/ 打断 / 冷场开题 / 沉默节流    │
├──────────────────────────────────────────────────────┤
│ Agent 层                                              │
│   N 个 ReActAgent（人设来自 DB，可热重载重建）             │
│   记忆服务（RAG 召回 + rerank，注入发言 prompt）           │
├──────────────────────────────────────────────────────┤
│ 持久层：单文件 SQLite（WAL）                             │
│   messages / agents / groups / group_members /        │
│   token_usage / shared_facts / agent_impressions /     │
│   memory_state / chat_state / decision_logs / vec0    │
├──────────────────────────────────────────────────────┤
│ AgentScope-Java 2.0.4                                 │
│   ReActAgent / Msg / AgentEvent 流 / MultiAgentFormatter│
│   结构化输出 / stateStore                              │
└──────────────────────────────────────────────────────┘
```

**一个关键取舍**：AgentScope 自带的 MsgHub / Pipeline 是"协作完成任务"式编排，本项目**不使用**。
群聊模拟要的是"谁在什么时候说话、什么时候闭嘴"，这属于产品体验的核心，调度逻辑必须自研；
框架只负责 Agent 本体、消息模型、流式事件、多 agent 格式化、结构化输出。

### 5.2 包结构

```
src/main/java/com/example/agentscopegroupchat/
├── GroupChatApplication.java   启动类
├── config/     AppProperties（配置绑定）/ SqliteDataSource（自建 DataSource + WAL）
│               DotenvEnvironmentPostProcessor（.env 加载）/ ModelConfigService（管理台读写模型配置 + 热更新）
│               SecurityTokenInterceptor（可选访问 token）/ NativeLibraryResolver（sqlite-vec DLL 提取）
│               WebMvcConfig / AppConfig（线程池）
├── web/        ChatController / GroupController / StreamController(SSE) / AgentController
│               StatsController / ConfigController / DecisionController / ApiExceptionHandler
├── scheduler/  GroupScheduler（核心调度：门控→裁决→队列→级联→打断）
│               GroupRuntime（每群的运行期状态）/ QuotaArbiter / MentionParser / IdleTopicTrigger
├── agent/      AgentRegistry（人设 → ReActAgent，支持热重载）/ Prompts（中文 prompt 组装）
├── memory/     MemoryService / MemoryExtractor / MemoryRetriever / VectorStore（vec0 + 纯 Java 兜底）
├── model/      ModelProvider（OpenAI 兼容模型构建）/ EmbeddingClient / RerankClient
├── store/      schema.sql + SchemaInitializer（建表 + 老库迁移）+ 各 DAO
│               （Message / Agent / Group / TokenUsage / Memory / ChatState / DecisionLog）
├── event/      SseHub（SSE 事件广播 + 断线补拉）
├── holiday/    HolidayCalendar（法定节假日/调休查询 + 一天一次缓存；失败降级）
├── dto/        请求/响应/结构化输出 POJO（GateDecision / MemoryExtraction / AgentDef …）
└── util/       TimeUtil / FloatBlobUtil
src/main/resources/
├── application.yml             应用配置（结构 + 默认值）
├── db/schema.sql               建表脚本（幂等）
├── native/windows-x86_64/vec0.dll   sqlite-vec v0.1.9
└── static/                     前端静态单页（index.html / admin.html / css / js）

start.bat                       一键启动（Windows：首启弹窗配模型 → 自动启动 → 开浏览器）
tools/setup-env.ps1             首启配置窗口（WinForms；也支持 -Headless 无界面写 .env）
.env.example                    配置占位符（真实凭据放 .env，不进版本库）
```

### 5.3 数据库表

单文件 SQLite（WAL），全部共用一个 `.db` 文件：

| 表 | 关键字段 | 说明 |
|---|---|---|
| `messages` | id, group_id, sender_id, sender_type(HUMAN/AGENT/SYSTEM), sender_name, content, recalled, created_at | 消息账本 |
| `agents` | id, name, avatar, persona, verbosity, emoji_tendency, response_speed, eagerness_bias, relationships(JSON), active, created_at, updated_at | 人设卡（DB 为唯一真源） |
| `groups` | id, name, avatar, notice, is_sample, created_at, updated_at | 群 |
| `group_members` | group_id, agent_id, joined_at | 群成员（多对多） |
| `token_usage` | id, ts, group_id, agent_id, purpose(GATE/SPEAK/MEMORY/EMBED), input_tokens, output_tokens | Token 流水 |
| `shared_facts` | id, group_id, fact, embedding(BLOB), created_at | 群共识 |
| `agent_impressions` | id, agent_id, about_user, impression, embedding(BLOB), created_at | 个人印象（跨群共享） |
| `memory_state` | group_id, last_extract_message_id, updated_at | 每群的记忆提取进度 |
| `chat_state` | group_id, stopped, updated_at | 每群的停止开关 |
| `decision_logs` | id, message_id, agent_id, want_to_speak, eagerness, gist, outcome(SELECTED/SKIPPED/SILENT), reason, created_at | 决策透视 |
| `vec_shared_facts` / `vec_agent_impressions` | vec0 虚拟表 | sqlite-vec KNN（失败时走纯 Java） |

老库升级由 `SchemaInitializer.migrate()` 负责：用 `ALTER TABLE ADD COLUMN` 补缺的列，然后回填、再建索引
（索引不能写在 `schema.sql` 里，否则老库会先于补列执行而报 "no such column"）。整个过程幂等，可以放心重复启动。

---

## 6. 接口

前端就是这些接口的消费者，也可以直接用 curl / 脚本调用（`groupId` 省略时一律回退到示例群）。

**错误约定**：参数错误（缺必需参数、类型不对、请求体不是 JSON、Content-Type 不对）一律返回 **4xx** +
`{"error":<状态码>,"message":"<说明>"}`，`message` 会指出是哪个参数出了问题；只有真正的服务端故障才是 `500`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/groups` | 群列表（成员数 / 消息数 / 最后一条消息 / 是否已停止） |
| POST | `/api/groups` | 新建群聊 `{name, avatar, notice, agentIds[]}` |
| GET/PUT/DELETE | `/api/groups/{id}` | 群详情 / 改名改公告 / 解散（示例群不可解散） |
| GET | `/api/groups/{id}/members` | 群成员 |
| POST | `/api/groups/{id}/members` | 拉人进群 `{agentIds[]}`（写加入提示条并触发打招呼） |
| DELETE | `/api/groups/{id}/members/{agentId}` | 把成员移出群 |
| POST | `/api/messages` | 真人发消息 `{content, groupId}`（返回 messageId，异步触发该群调度） |
| GET | `/api/messages?groupId=&before=&limit=` | 历史分页 |
| POST | `/api/messages/{id}/recall` | 撤回消息 |
| GET | `/api/stream` | **SSE**：`message` / `typing`（带 `phase`: `waiting`=轮到它、酝酿中；`composing`=正在输入） / `thinking`（门控阶段） / `notice`（实时提示：被 @ 没理睬 / 全员已读不回，不落库） / `decision` / `usage` / `system` / `recall` / `state` / `groups` / `model`，每个事件都带 `groupId` |
| GET/POST/PUT/DELETE | `/api/agents` | Agent CRUD（DELETE = 逻辑删除）；新建不会自动进群 |
| POST | `/api/agents/reload` | 人设热重载 |
| GET | `/api/stats/tokens` | 全部群聊总览（含 `byGroup` 按群聚合） |
| GET | `/api/stats/tokens?groupId=` | 单群明细（按成员 / 按用途 / 今日 / 累计） |
| GET/POST | `/api/config/chat-state?groupId=` | 停止 / 恢复某个群的聊天 |
| GET | `/api/config/meta?groupId=` | 前端元信息（群名、成员、模型是否配置…） |
| GET | `/api/config/model` | 当前模型配置（**API Key 只回显掩码**）+ `.env` 路径 + 被系统环境变量占住的键 |
| PUT | `/api/config/model` | 保存模型配置：写回 `.env` 并**热更新**（字段传 `null` = 该项不动；`clearApiKey:true` = 清空；`vecDimension` 需重启） |
| GET | `/api/decisions?messageId=` | 决策透视数据 |

---

## 7. 测试

```bash
mvn test
```

当前 87 个测试全绿。覆盖：

| 测试 | 覆盖内容 |
|---|---|
| `QuotaArbiterTest` | 50% 配额裁决、表达欲接近时随机洗牌 |
| `MentionParserTest` | @提及解析（含昵称前后缀、多@） |
| `VectorStoreTest` | 关掉 sqlite-vec，强制走纯 Java 余弦兜底，验证排序与过滤 |
| `FloatBlobUtilTest` | float32 BLOB 编解码与余弦 |
| `PromptsTest` | 中文 prompt 组装（门控 / 发言 / 冷场 / 记忆注入 / **当前时间与时间标签**） |
| `TimeUtilTest` | 星期与工作日判定、时段口语（凌晨/上午/下午/晚上…）、`【当前时间】` 文案、聊天记录的`[今天/昨天/M月d日]`标签 |
| `HolidayCalendarTest` | 节假日/调休映射（国庆节假期 / 调休上班 / 周末）、接口不可达时降级不抛异常、**并行门控只打一次接口**（起一个本地 HttpServer 数请求数） |
| `DotenvEnvironmentPostProcessorTest` | `.env` 各种写法与优先级 |
| `ModelConfigServiceTest` | 模型配置读写：只改 `.env` 受管行（注释 / 自定义变量保留）、API Key 三态（留空 / 覆盖 / 清空）、"就地改内存 + 重置模型缓存"的热更新语义 |
| `AppPropertiesBindingTest` | 守住 `application.yml` 占位符 ↔ `.env` 键名的契约（少一个占位符就会静默失效） |
| `MentionMissTextTest` | "被 @ 但没回你"的提示文案：装没看见(没理睬你) 与 被配额刷掉(没抢到话头) 分开措辞 |
| `MultiGroupSchemaTest` | 跑真实 `schema.sql`：示例群自动创建、拉人不复制人设、消息与统计按群隔离、解散只清自己的数据、**老库补列并回填历史 token** |
| `ApiExceptionHandlerWebTest` | 参数绑定错误的 HTTP 契约：缺参 / 类型不对 / 路径里的 id 不对 → **400 而非 500**、Content-Type 不对 → 415，且报错信息要指出是哪个参数（用真实 Controller + 异常处理器起 MockMvc） |

---

## 8. 备份与排错

### 备份 / 迁移

所有数据都在 `data/chat.db` 一个文件里（加上 WAL 的 `-wal` / `-shm`）。
备份就是复制文件；**停掉应用再复制**最稳妥。

想换机器：要带走的是两样东西——`data/chat.db`（全部数据）和 `.env`（模型凭据与端点）。
`.env` 里有密钥，**别传公开渠道**；新机器上也可以不拷 `.env`，改用 `start.bat` 弹窗或管理台重新填。

### 常见问题

**Q：模型参数不会填 / 不想手写 `.env`？**
Windows 直接双击 `start.bat`，首次运行弹窗里选一个「常用服务商」，接口地址和模型名自动填好，你只粘 Key。
也可以打开 `http://localhost:8080/admin.html#model`（管理台 → 模型配置），选服务商 → 填 Key → 保存，**立即生效**。
各服务商的 baseUrl / 模型名 / 向量维度对照见 [1.2 配置](#12-配置)。

**Q：群里没人说话？**
按顺序检查：
1. 模型配了没：没配时群聊页顶部会有"模型未配置"横幅，点它直达**管理台 → 模型配置**，填完即生效（消息照常落库，只是不触发发言）
2. 该群是不是被 ⏸ 停止聊天了（会话列表会显示"已停止"）
3. 是不是触发了沉默节流（连续几轮自发开题没人接话后会暂停，真人发一条消息即恢复）
4. 看启动日志有没有 `chat 模型未配置` / 模型调用报错

**Q：agent 抢话太凶 / 太冷淡？**
调 `speak-quota-percent`、`cascade-max-depth`、门控模型的 prompt 与人设的「表达欲基准」。

**Q：AI 说的时间和现实对不上（比如下午却说"大晚上的"）？**
现在每个 prompt 都会注入真实的 `【当前时间】`（年月日 + 星期 + 时段 + 是否上班），并明确要求"以此为准、不要臆测"；
聊天记录也会在跨天 / 断档 >30 分钟处标上 `[今天 14:43]`。若仍出错，通常是模型没听从指令，可换一个更听话的发言模型。
"是否上班"默认会查一次外部节假日接口，因此**国庆/春节/调休都能判准**（"国庆节假期""调休上班"）；接口不通时
自动退回"周一~周五=工作日"。不想让本机访问外部接口，设 `APP_HOLIDAY_ENABLED=false` 即可。

**Q：模型网关报 400？**
分两种情况，都在**管理台 → 模型配置 → 进阶**里改一个勾选框就能解决：
- 报 `tool_choice` 相关 → 这家的"思考模式"不接受强制工具调用，把「关闭思考模式」**勾上**（`disable-thinking: true`，默认值）
- 报 `Unknown / Unrecognized request argument: enable_thinking` → 这家网关不认这个非标准字段
  （DeepSeek 官方 / OpenAI / 硅基流动 / 智谱都属于这类），把它**取消勾选**

实在不支持，代码里还有 `json_object` + 宽松解析两层兜底，功能不会断。

**Q：日志里 `sqlite-vec 加载失败`？**
正常降级，不影响功能，只是 KNN 改走纯 Java 余弦（这个数据量级基本无感）。
想强制走兜底可以设 `app.vec.extension-path: none`。

**Q：改了配置，什么时候生效？**

| 改了什么 | 生效时机 |
|---|---|
| 人设、群资料、群成员 | 保存后**立即生效**（内部热重载重建 agent 实例） |
| **模型配置**（baseUrl / API Key / 模型名 / 关闭思考） | 管理台保存后**立即生效**，同时写回 `.env`（重启后也还在） |
| 向量维度 | 写回 `.env`，但 vec0 表维度在建库时就固定了 → **必须重启**，且旧记忆向量与新维度不兼容 |
| 其它 `application.yml` 项（配额、级联、节流…） | 改文件后**需要重启**（IDE 里运行的 JVM 不会热部署前端 / 配置） |

**Q：管理台明明保存成功了，重启后参数又变回去了？**
说明那个键是**系统环境变量**设的——环境变量优先级高于 `.env`。
管理台的状态区会用黄色告警把被占住的键名列出来，去改系统环境变量即可。

**Q：换了 embedding 模型，记忆写入报错 / 向量维度不匹配？**
向量模型输出多少维，`APP_VEC_DIMENSION` 就必须是多少（bge-m3 / text-embedding-v4 = 1024，`text-embedding-3-small` = 1536，
智谱 `embedding-3` = 2048，`nomic-embed-text` = 768）。改了维度要**重启**；
如果库里已有旧维度的记忆向量，建议清空记忆表后重新积累。

**Q：端口 8080 被占用？**
改 `application.yml` 里的 `server.port`，或启动参数 `--server.port=9090`。

**Q：想让公网也能访问？**
一定要先设 `APP_ACCESS_TOKEN`（非空后 `/api/**` 必须带 `X-Access-Token` 请求头，SSE 用 `?token=`），
再交给 Nginx 反代（注意给 SSE 关掉缓冲：`proxy_buffering off;`）。

**Q：token 花得有点多？**
1. gate 换成更便宜的 flash 级模型（门控调用量最大）
2. 调小 `cascade-max-depth` 和 `speak-quota-percent`
3. 靠沉默节流 `max-idle-rounds-without-human` 兜住"没人看还一直在聊"的情况
4. 用统计页按群、按成员找出谁最费钱