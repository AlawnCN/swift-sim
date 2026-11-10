# SWIFT 报文模拟器 — 保函 / 备用信用证 / 跟单信用证 / 跟单托收

> 用途：在没有真实 SWIFT 网关、也没有对手行测试环境的情况下，为你的贸易融资系统（肯尼亚市场）提供一个
> **"SWIFT 网关 + 若干虚拟参与行"** 的本地模拟器：你的系统发报 → 模拟器校验并 ACK/NAK → 投递给虚拟行 →
> 虚拟行按 YAML 场景在 N 秒后回报。覆盖 **见索即付保函 (Demand Guarantee)、备用信用证 (SBLC)**（MT + MX），以及 **跟单信用证 (Cat 7 MT700 系列) 与跟单托收 (Cat 4)**（仅 MT）。
>
> ⚠️ 这只是测试工具：所有 BIC 均为**虚构**测试代码，所有报文内容无任何法律/金融效力；校验规则**不是** SWIFT 官方网络校验 (NVR) 的完整实现。

---

## 1. 架构

```
   你的系统 (SUT, BIC TSTAKEN0XXX)
     │  REST POST /api/messages  或  文件投递 data/inbox/*.fin|*.xml
     ▼
 ┌────────────────────────── swift-sim (Spring Boot 3.5, Java 21, 127.0.0.1:8080) ───────────────────────────┐
 │ 1. 格式自动识别 MT({1: 开头) / MX(< 开头)                                                                     │
 │ 2. 校验  MT: Prowide Core (pw-swift-core SRU2025) 解析 + 必填字段/格式/严格日期/ISO 4217 + MT765 与 MT760 条件规则 │
 │          MX: iso20022.org 官方 XSD 校验 (src/main/resources/xsd) + Prowide ISO 20022 (pw-iso20022) 建模        │
 │ 3. 网关 ACK / NAK（FIN 风格 {451:0}/{451:1}{405:...}，会话号/ISN/MIR；MX 用简单 XML 回执）                     │
 │ 4. 延迟投递 → output 格式 + MOR/OSN + {5:{CHK:}{TNG:}} → outbox、webhook、投递通知(DLV，仿 MT011)              │
 │ 5. 收报方"业务系统"：交易台账 (ledger) 校验角色/状态/金额 → 更新状态机，或产生业务拒绝 (MT759 / tsrv.019 REJT)   │
 │ 6. 场景引擎：收报方为虚拟行 → 审核模式? 入审核队列 : 匹配场景 → 持久化的延迟回复 (pending_response) → 回报        │
 │ 7. 报文、台账、会话、延迟回复、审核队列都写入 H2 文件库 data/db/swiftsim.mv.db（重启可恢复）；Web UI 在 /         │
 └────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
     ▲  GET /api/messages?to=TSTAKEN0XXX   |  data/outbox/<BIC>/*.fin|xml|txt   |  可选 webhook POST(JSON)
```

代码结构（`src/main/java/com/example/swiftsim`）：

| 包 | 说明 |
|---|---|
| `config` | `participants.yml` / `scenarios.yml` 的 POJO（Lombok）与加载、场景激活状态 |
| `parse` | `MtHandler`（Prowide MT 解析与检查）、`MxHandler`（XSD 校验 + Prowide MX 解析 + 变量提取） |
| `gateway` | `GatewayService`：校验 → ACK/NAK → 投递(output 格式) → 台账 → outbox / webhook / DLV → 发布 `DeliveredEvent`；`SessionService`（LT 会话/ISN/OSN） |
| `scenario` | `ScenarioEngine`：场景匹配、模板渲染、延迟/重复/超时、kickoff（虚拟行先发报） |
| `ledger` | `LedgerService`：交易台账、状态机、业务校验 (BIZ-xxx)、到期扫描、`Facts`（从 MT/MX 抽取业务要素）、`BusinessClock` |
| `review` | 审核模式与审核队列（`ReviewService`、`ReviewModeService`） |
| `web` | REST API (`/api/**`，`ApiController` + `LedgerController`)，静态页面 `src/main/resources/static/index.html`（报文 / 交易台账 / 审核队列三个页签） |
| `files` | `DropFolderWatcher`：每 2 秒扫描 `data/inbox` |
| `ops` | 管理端 (8081) 与 AI API (8082)：多端口 `PortsConfig`、按端口路由/鉴权 `PortGuardFilter`、`SecretsService`（账号/API key 文件）、`AuditService`、`OpsService`（报文检索/分析、dry-run 校验、业务核对）、`PurgeService`、`AdminController`、`AiController`、`McpController`；前端 `static/admin/` |

依赖：Spring Boot 3.5.16（web / data-jpa / validation）、H2、Lombok、jackson-dataformat-yaml、
`com.prowidesoftware:pw-swift-core:SRU2025-10.3.20`、`com.prowidesoftware:pw-iso20022:SRU2025-10.4.0`。
> 注意：Spring Boot 3.5 默认管理的 commons-lang3 为 3.17，Prowide SRU2025 需要 ≥3.20（`org.apache.commons.lang3.Strings`），
> pom 中已用 `<commons-lang3.version>3.20.0</commons-lang3.version>` 覆盖。你自己的工程集成 Prowide SRU2025 时也要注意这一点。

## 2. 运行

本机已安装：Temurin JDK 21（`/opt/tools/jdk`）、Maven 3.9.9（`/opt/tools/maven`），未使用 Docker（box 上没有 Docker）。

```bash
cd /workspace/swift-sim
mvn -B -DskipTests package      # 生成 target/swift-sim-0.1.0.jar
./run.sh                        # nohup 后台启动 8080/8081/8082，日志 data/app.log，PID data/app.pid；打印地址和凭据文件路径
./stop.sh                       # 停止
python3 tests/e2e.py            # 端到端测试（服务需已启动）
```

* 默认只监听 `127.0.0.1:8080`（`application.yml` 中 `server.address`）；如需局域网访问见下文「部署 / 监听地址」。
* Web UI：<http://localhost:8080/>（报文日志、按交易参考号过滤、查看原文/NAK 原因、激活/触发场景、以任意参与行身份发报、加载样例）。
* 配置目录 `./config`、数据目录 `./data`、样例目录 `./samples`（均可通过 `--sim.config-dir=` 等覆盖）。
* 修改 YAML 后无需重启：`curl -X POST localhost:8080/api/admin/reload`。

## 2A. 管理端（端口 8081）与 AI 接入 API（端口 8082）

启动后同一个进程监听三个端口，**默认都只绑定 127.0.0.1**（如需对外监听见下文「部署 / 监听地址」）：

| 端口 | 用途 | 鉴权 |
|---|---|---|
| 8080 | 报文网关 / 原有 REST API `/api/**` / 原 Web UI（不变；`/admin/**`、`/ai/**` 在此端口返回 404） | 无（默认仅本机） |
| 8081 | **中文管理端** <http://127.0.0.1:8081/>（也可在此端口调用 `/api/**`） | 登录（cookie），账号密码文件见下 |
| 8082 | **AI API** `http://127.0.0.1:8082/ai/v1`，OpenAPI `/ai/v1/openapi.json`，MCP `/ai/v1/mcp` | `Authorization: Bearer <key>` |

**凭据文件**（首次启动自动生成随机值，权限 600，日志中只打印路径，不打印内容；修改后自动生效，无需重启）：
* `config/secrets/admin-console.properties`：`username=admin`、`password=…`（管理端登录）。
* `config/secrets/ai-api-keys`：每行 `<名称> <read|operator> <key>`，默认生成 `ai-readonly`（只读）和 `ai-operator`（可处理审批、发起场景）两把。
  增加/吊销 key：编辑该文件即可（key 至少 16 位）。查看：`awk '{print $1, $2}' config/secrets/ai-api-keys`（不要把 key 粘贴到聊天/工单）。
* 端口、地址、目录可配置：`--sim.admin.port= --sim.ai.port= --sim.admin.address= --sim.secrets-dir= --sim.protected-refs=`；`--sim.admin.enabled=false` / `--sim.ai.enabled=false` 可关闭。

**管理端功能**（纯 HTML/JS，无外部 CDN）：
* 概览：FIN 会话号 / ISN / OSN、报文数（NAK、业务拒绝）、三类台账按状态统计、待审批数、激活场景、最近报文。
* 报文：按机构 / 方向 / 类型 / 编号 / 网关状态 / 业务状态（含"业务拒绝/不一致"）/ 日期过滤；详情含原文与投递原文（output 格式）、解析字段、校验结果（SIM-/BIZ- 代码）、关联业务与前后报文；可"复制到发报页"。
* 业务台账：保函 / 信用证 / 托收列表；详情含当前状态与预期下一步、**核对结果**（与 AI API 相同的 reconcile）、修改/索赔/交单/金额变动、事件时间线、关联报文、导出 JSON。
* 审批队列：查看待审批报文及台账判断；**① 生成草稿预览**（不发送）→ **② 按此决定发送**，或修改草稿后"编辑后发送 (CUSTOM)"；业务拒绝；忽略。
* 机构：参与行列表，一键开/关"审批模式"。
* 模拟场景：按组列出，激活；带 kickoff 的场景可指定格式 / 编号 / 发报方 / 收报方发起。
* 发报 / 校验：以任意机构（SUT 或虚拟行）身份发报，走正常网关（校验、ISN/OSN、ACK/NAK、投递、台账、场景，channel=ADMIN）；"只校验"为 dry-run，不发送、不占序号、台账回滚。
* 运行日志：`data/app.log` 实时追踪（2 秒轮询、级别/关键字过滤）。审计日志：`data/audit.log`（JSON lines：管理端所有修改操作和登录、AI operator 操作、被拒绝的 AI 请求）。
* 管理工具：营业日设置/恢复、到期扫描、重新加载配置、**在线数据库备份**（H2 `BACKUP TO`，文件在 `data/backups/`，可下载）、全部/单笔 JSON 导出、
  **清理测试数据**（两阶段：先预览数量并得到确认码，再输入确认码执行；只删所选编号的台账、报文、审批项、计划回复；**不动会话 / ISN / OSN**；
  受保护编号 `KEGTEE2026000101`（`sim.protected-refs`）即使被选中也会跳过，除非在"包含受保护编号"中明确填写）。
* 修改类请求需带请求头 `X-Sim-Admin: 1`（前端自动带，防 CSRF）；连续 10 次登录失败锁定 5 分钟。

**AI API**（给本助手或其他 AI/自动化使用，紧凑稳定的 JSON，中英文说明）：
* `GET /ai/v1/messages`（`sinceId` 游标增量拉取，返回 `cursor`/`hasMore`）、`GET /ai/v1/messages/{id}`（原文、解析字段、校验发现、关联业务、回复）。
* `GET /ai/v1/business`、`GET /ai/v1/business/{ref}`（状态、金额、明细、时间线、预期下一步）、**`GET /ai/v1/business/{ref}/reconcile`**（NAK、业务拒绝、21 场链接、
  余额/已用/已付、到期、修改序号、缺失回复、待处理项 → `findings[severity, code, message, evidence]`）。
* **`POST /ai/v1/validate`**：任意 MT/MX（或 block 4 + from/to/mtType）→ 语法 + 网络规则 + 寻址 + 基于当前台账的业务规则（dry-run，回滚），给出 `verdict`。
* `GET /ai/v1/scenarios`、`GET /ai/v1/review-queue[/{id}]`、`POST …/{id}/preview`；operator：`POST …/{id}/respond`、`POST /ai/v1/scenarios/{id}/trigger`。
* 参考数据：`/ai/v1/reference/rule-codes`（SIM-/BIZ- 代码目录）、`/state-machines`、`/message-types`、`/participants`，`/ai/v1/sessions`、`/ai/v1/pending-responses`。
* **只读 key 不能做任何修改**（返回 403 `AI-SCOPE` 并记审计）；AI API 没有清理数据、改序号的接口。
* **MCP**：`POST /ai/v1/mcp`（Streamable HTTP，JSON-RPC 2.0：initialize / tools/list / tools/call），工具 list_messages、get_message、list_business、get_business、
  reconcile_business、validate_message、list_scenarios、list_review_queue、preview_review_response、get_reference、respond_review(operator)、trigger_scenario(operator)。

**给其他 AI 的材料**在 `docs/ai/`：`SKILL.md`（技能说明，含工作流与安全规则）、`system-prompt-zh.md` / `system-prompt-en.md`（系统提示词）、
`mcp-config.md`（Cursor / Claude Desktop / Claude Code 配置示例）、`curl-examples.md`、`openapi.json`（由 `tests/gen_openapi.py` 生成，与 `/ai/v1/openapi.json` 相同）。
从其他机器接入：`ssh -N -L 8081:127.0.0.1:8081 -L 8082:127.0.0.1:8082 <user>@<主机>`，然后用同样的本地地址。

### 部署 / 监听地址

三个端口（8080 网关、8081 管理端、8082 AI API）**默认都只绑定 `127.0.0.1`**，只能本机访问。

如需在局域网内访问（监听所有网卡），任选其一：

```bash
# 方式一：启动参数覆盖（run.sh 会把参数原样传给 jar）
./run.sh --server.address=0.0.0.0 --sim.admin.address=0.0.0.0 --sim.ai.address=0.0.0.0
```

* 方式二：修改 `src/main/resources/application.yml` 中的三处 `address:`（`server.address`、`sim.admin.address`、`sim.ai.address`），然后重新打包。

访问地址（`<host>` 为部署机器的 IP 或主机名）：网关 `http://<host>:8080`、管理端 `http://<host>:8081`、AI API `http://<host>:8082/ai/v1`。

**安全提示**：
* **8080 网关本身没有任何鉴权**（并且提供 `DELETE /api/messages`、`DELETE /api/undertakings` 等删除接口），**不要暴露到公网**，仅在可信内网中开放。
* 8081 管理端需要登录，账号密码在 `config/secrets/admin-console.properties`（首次启动自动生成）。
* 8082 AI API 需要 `Authorization: Bearer <key>`，key 在 `config/secrets/ai-api-keys`。

## 3. 虚拟参与行（`config/participants.yml`，全部虚构）

| ID | BIC | 角色 |
|---|---|---|
| `SUT_BANK` | `TSTAKEN0XXX` | **你的系统**（`sut: true`，可设置 `webhookUrl`） |
| `ISSUING_BANK` | `TSTBGB20XXX` | 虚拟开立行/担保行 |
| `ADVISING_BANK` | `TSTCKEN0XXX` | 虚拟通知行 |
| `BENEFICIARY_BANK` | `TSTDKEN0XXX` | 虚拟受益人银行/交单行 |
| `APPLICANT_BANK` | `TSTECN20XXX` | 虚拟申请人银行（反担保/指示行） |
| `REIMBURSING_BANK` | `TSTFUS30XXX` | 虚拟偿付行（MT740/742/747） |
| `CONFIRMING_BANK` | `TSTGKEN0XXX` | 虚拟保兑行 / 第二通知行 / 转让行 |
| `COLLECTING_BANK` | `TSTHCN20XXX` | 虚拟代收行（你的系统为托收行时） |
| `REMITTING_BANK` | `TSTJIN20XXX` | 虚拟托收行（你的系统为代收行时） |
| `TRANSFEREE_BANK` | `TSTKAE20XXX` | 虚拟第二受益人银行（MT720 转让） |

BIC 规则：银行代码均为 `TST?`，位置代码第二位为 `0` 或数字（SWIFT 测试/培训 BIC 的惯例写法），且满足 ISO 20022 `AnyBICIdentifier` 正则。
**请勿替换为真实 BIC。**

添加参与行：在 `participants:` 下追加一项（`id / name / bic / role / sut / webhookUrl / deliveryNotifications / reviewMode`），然后 reload。
新增配置：`gateway.enforceIsn / requireAppHdr / testAndTrainingTrailer / fieldFormatValidation`，以及 `ledger:` 段
（`allowConcurrentAmendments / allowConcurrentDemands / rejectUnknownReferences / preIssuanceMt759Codes / rejectDelaySeconds / expirySweepMs`），见文件内注释。
发报方与收报方都必须在目录中，否则 NAK（`SIM-A01` / `SIM-R01`）。

## 4. 支持的报文

### MT（Prowide `com.prowidesoftware.swift.model.mt.mt7xx.*`，SRU2025 即 SR2021+ 保函改版后的结构）

| MT | 名称 | 模拟器检查的必填字段（取自 Prowide SRU2025 类结构） |
|---|---|---|
| 760 | Issue of a Demand Guarantee / Standby LC | 15A, 27, 22A, 15B, 20, 30, 22D, 40C, 23B, 52a, 59a, 32B, 77U。条件：22D=STBY 时序列内必须有 49；22A=ISCO/ICCA 时必须有序列 C（当地/请求保函），否则不允许序列 C |
| 761 | Continuation of MT760 | 27, 20, 52a，以及 77U 或 77L 至少一个。文本追加到保函 additionalTerms，不改金额 |
| 775 | Continuation of MT767 | 27, 20, 21, 26E, 52a，以及 77U 或 77L 至少一个 |
| 767 | Amendment | 15A, 27, 21, 22A, 15B, 20, 26E, 30, 52a |
| 768 | Acknowledgement | 20, 21, 30 |
| 769 | Advice of Reduction or Release | 20, 21 |
| 765 | Demand | 20, 21, 52a, 22G, 32B；网络规则：22G=PAYM 时不允许 31E；49A=INCP ⇔ 必须有 77 |
| 785 | Non-Extension Notification | 20, 52a, 31C, 59a |
| 786 | Demand Refusal Advice | 20, 52a, 30, 32B, 77J |
| 787 | Amendment Response | 20, 21, 52a, 26E, 23R |
| 759 | Ancillary Trade Structured Message | 27, 20, 22D, 23H, 45D |
| 202 | 银行间资金腿（默认）。block 3 `{121:}` UETR 必填 | 20, 21, 32A, 58a |
| 792 | Request for Cancellation | 20, 21, 11S, 79。只记 CANCELLATION_REQUESTED，不撤销不可撤销的保函/信用证/托收（那要走 MT767/707/747） |
| 796 | Answers | 20, 21, 76 |

其他检查：20/21/23 为 1–16 字符且不能以 `/` 开头/结尾、不能含 `//`；日期字段 YYMMDD 按 `ResolverStyle.STRICT` 解析（`260231` 直接 NAK，不会被改成 2 月 28 日）；金额 `3!a15d` 且必须用小数逗号，币种须为已知 ISO 4217，小数位须等于该币种的辅币位数（T40/T43，`SIM-F07`）；26E 为 1–3 位数字；
22D/40C/23B/22G/23R/23H 的代码不在已知列表时只给**警告**（不 NAK，见 TODO）。

交易参考号 (txRef) 提取规则：MT768/769/202 取 :21:，MT759 取 :23:（无则 :21:/:20:），其余取 :20:（保函编号）。

另外（Stage 2/3，仅 MT）：跟单信用证 MT700/701/705/707/708/710/711/720/721/730/732/734/740/742/744/747/750/752/754/756/759/799，
托收 MT400/410/412/416/420/422/430/450/455/456/499，以及与之关联的 MT202/MT103（见 5D）。

### MX（`com.prowidesoftware.swift.model.mx.MxTsrv*/MxTsin*`，XSD 来自 iso20022.org）

`tsrv.001` UndertakingIssuance、`tsrv.005` UndertakingAmendment、`tsrv.008` UndertakingAmendmentResponse、
`tsrv.010` NonExtensionRequest、`tsrv.011` NonExtensionNotification、`tsrv.012` TerminationNotification、
`tsrv.013` UndertakingDemand、`tsrv.014` ExtendOrPayRequest、`tsrv.015` ExtendOrPayResponse、`tsrv.016` DemandRefusalNotification、
`tsrv.017` DemandWithdrawalNotification、`tsrv.019` UndertakingStatusReport、`tsin.005` UndertakingApplication —— 全部为 **.001.01** 版本
（iso20022.org 与 Prowide 中目前仅有该版本）。

资金腿（CBPR+，与 MT202/MT103 并存，**MT 仍是场景默认**）：`pacs.008.001.08`（客户汇款，对应 MT103）和 `pacs.009.001.08`（行间汇款）。`pacs.009` 含 `UndrlygCstmrCdtTrf` 时为 **COV**，否则为 **CORE**。`EndToEndId` 等于贸易参考号（保函号 / 交单或索偿号 / 托收号），`IntrBkSttlmAmt` 是入账金额。这两类不做完整 XSD（schema 未随包提供），模拟器检查报文骨架、UETR、结算金额和 CORE/COV 是否与 `BizSvc` 一致。

* 可以只发 `<Document>`，也可以带 BAH：`<RequestPayload><AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">…</AppHdr><Document>…</Document></RequestPayload>`。
  AppHdr (head.001.001.02) 同样做 XSD 校验；其 `Fr/To` BICFI 作为发/收报行。无 AppHdr 时用 `?from=&to=`，再没有则用 `gateway.defaultMxSender/Receiver`。
* 模拟器发出的 MX 由 Prowide 生成（`AbstractMX.parse` + `AppHdrFactory.createBusinessAppHdrV02` + `message()`），带 `head:`/`tsrv:` 前缀。

## 5A. 交易台账与生命周期状态机（`ledger` 包）

每一笔保函/备用证（以 MT `:20:` 保函编号 / MX `UdrtkgId/Id` 为键，**模拟器内全局唯一**——简化处理）在 H2 中有一份持久化台账：
参与方及角色、币种、原始金额/当前金额/**余额 (outstanding)**、开立日、到期类型/到期日、规则 (URDG/ISP…)、受益人/申请人、
修改记录（PENDING/ACCEPTED/REJECTED/EFFECTIVE）、索赔记录（RECEIVED/ACCEPTED/PARTIALLY_PAID/PAID/REFUSED/EXTENDED/WITHDRAWN）、余额变动（ISSUE/INCREASE/DECREASE/PAYMENT/REDUCTION/RELEASE/REQUEST）、
以及**状态历史**（每条事件带营业日、关联报文 ID）。台账在**收报方"业务系统"收到报文时**（投递时）更新，网络层 ACK 与业务处理完全分离。

### 状态
```
                (tsin.005) APPLIED ──MT760/tsrv.001──┐
                                                     ▼
 MT760/tsrv.001 ───────────────────────────────▶ ISSUED ──MT768 / tsrv.019 ACCP(非开立行→开立行)──▶ ADVISED
                                                     │
   MT767/tsrv.005 ─▶ AMENDMENT_PENDING ─MT787/tsrv.008 ACPT─▶ AMENDED (金额/到期日生效)
                                       └─ REJT ─▶ (回到原状态, 修改记为 REJECTED)
   tsrv.005 BnfcryCnsntReqInd=false 或 "延期或付款"期间的延期修改 ─▶ 直接生效 (EFFECTIVE)   [近似, 见 TODO]
   MT765/tsrv.013 PAYM ─▶ DEMAND_RECEIVED ─tsrv.019(DMND/ACCP)─▶ DEMAND_ACCEPTED ─MT202/MT103 或 pacs.008/pacs.009─▶ PAID
                         └─付款金额 < 索赔额─▶ PARTIALLY_PAID（未付部分保持可付，不记为已全额付款）
                                          └─MT786/tsrv.016─▶ REFUSED      └─tsrv.017─▶ (索赔 WITHDRAWN)
   MT765/tsrv.013 PAEX ─▶ EXTEND_OR_PAY_PENDING ─延期修改─▶ EXTENDED │ ─付款─▶ PAID │ ─拒付─▶ REFUSED
   MT769 (32B=减额) ─▶ REDUCED；余额减到 0 ─▶ RELEASED          tsrv.012 ─▶ TERMINATED
   MT785/tsrv.011 只记录"不展期通知"标志；到期日过后（营业日）─▶ EXPIRED（有未决索赔时暂不过期）
```
状态 = 里程碑（ISSUED/ADVISED/AMENDED/PAID/REFUSED/EXTENDED/REDUCED/RELEASED/EXPIRED/TERMINATED/REQUESTED）+ 叠加的"未决事项"
（未决索赔 `RECEIVED` → `DEMAND_RECEIVED`，已受理待付款 → `DEMAND_ACCEPTED`，部分付款 → `PARTIALLY_PAID`，展期或付款 → `EXTEND_OR_PAY_PENDING`；否则有未决修改 → `AMENDMENT_PENDING`）。终态：RELEASED / EXPIRED / TERMINATED。

**反担保（MT760 `22A:ISCO` 或 `ICCA`）**记两笔义务：序列 B 是反担保本身（`obligationKind=COUNTER`，状态按正常开立走），序列 C 是请求对方开立的当地保函（`obligationKind=LOCAL`，状态 `REQUESTED`，开立行=收报行）。两笔用 `relatedRef` 互指。当地保函编号取叙述里的 `/LOCALREF/<编号>`，否则为 `L` + 反担保编号。当地行随后用这个编号发 MT760 `ISSU`，台账从 `REQUESTED` 改为 `ISSUED`（不重复记一笔开立金额）。

### 业务拒绝（虚拟行作为收报方时）
虚拟行收到与台账不一致的报文时，**网络层照常 ACK**，业务层回一条拒绝：MT 入站 → **MT759** (`23H:OTHERFNC`，45D 中列出 BIZ 码；格式为模拟器约定，见 TODO)，
MX 入站 → **tsrv.019** `Sts=REJT`（`StsCtgy=GENR` 占位）+ `AddtlInf` 原因。该报文 `businessStatus=REJECTED`，台账记录 `BUSINESS_REJECTED` 事件，不改状态。
**发给你的系统 (SUT) 的报文永远不被拦截**：若与台账不一致，只标记 `businessStatus=INCONSISTENT` 并记录 `INCONSISTENT_MESSAGE` 事件（意思是"你的系统应当拒绝它"）。

| 码 | 含义 |
|---|---|
| BIZ-U01 | 引用的保函编号不存在（含"开立前修改"）；`ledger.rejectUnknownReferences=false` 时放行 |
| BIZ-U02 | 重复的保函编号（MT760/tsrv.001 已存在；tsin.005 申请号重复） |
| BIZ-V00~V04 | 开立数据：缺编号 / 到期日早于开立日 / FIXD 无到期日 / 缺金额 / 到期日已过（按营业日） |
| BIZ-R01/R02 | 发报行 / 收报行不是该保函的参与方 |
| BIZ-R03/R04 | 角色不对：如修改/拒付/结算/不展期须由 ISSUER 发给非开立行；索赔/修改答复/撤回须发给 ISSUER；tsrv.014 须 ISSUER→APPLICANT；tsrv.015 须 APPLICANT→ISSUER |
| BIZ-R05 | 拒付/付款/撤回与原索赔的交单行不一致 |
| BIZ-S01 | 只有申请 (APPLIED) 尚未开立就发修改/索赔等 |
| BIZ-S02 | 到期后索赔 |
| BIZ-S03 | 终态（RELEASED/EXPIRED/TERMINATED）后的报文 |
| BIZ-A01 | 已有未决修改时再发修改（`ledger.allowConcurrentAmendments=false`） |
| BIZ-A02 | 修改序号不连续（期望 = 已有修改数 + 1） |
| BIZ-A03 | 修改答复但没有未决修改 / 序号不符 |
| BIZ-A04 | 修改答复代码无法识别（MT787 23R 非 ACPT/REJT） |
| BIZ-D01 | 索赔金额 > 余额 |
| BIZ-D02 | 索赔参考号重复 |
| BIZ-D03 | 已有索赔在审单中（`ledger.allowConcurrentDemands=false`） |
| BIZ-D04~D08 | 无未决索赔 / 索赔缺金额 / PAEX 请求的到期日不晚于当前到期日 / 对非 PAEX 索赔做延期或付款 / 付款金额 > 索赔金额 |
| BIZ-M01/M02 | 减额/付款 > 余额；修改减额 > 余额 |
| BIZ-C01 | 币种与保函币种不一致 |

MT759（自由格式附属报文）不做参与方/状态检查，只挂到历史上（常用于与非参与方如申请人银行往来）；`23H` 在 `ledger.preIssuanceMt759Codes` 中的 MT759 在开立前也可接收。
重复投递 (PDE / PssblDplct) 的副本若其原报文已处理 → `DUPLICATE_IGNORED`，不再次记账、不再回复。

### 角色从哪里来
MT760/tsrv.001：发报行 = ISSUER，收报行 = ADVISING（或 SECOND_ADVISING），52a/56a/57a、59A/受益人 BIC、50 或 Applcnt/Oblgr 的 BIC 若在参与行目录中则加入相应角色；
tsin.005：发报行 = APPLICANT，收报行 = ISSUER。线下关系（例如申请人银行）可用 `POST /api/undertakings/{ref}/parties` 补登记（真实世界中这来自线下协议）。

### 营业日
`POST /api/admin/business-date {"date":"2027-12-01"}` 设置模拟营业日（内存，重启恢复为系统日期）并立即做一次到期处理；
`@Scheduled` 每 `ledger.expirySweepMs`（默认 60s）自动扫描。所有事件都记录营业日。

### 场景与台账的联动
* 回报模板可用台账变量：`ldg_status` `ldg_outcome` `outstanding/outstandingIso` `ldg_amount` `ldg_issuerBic` `ldg_form`
  `ldg_expiry6/ldg_expiryIso` `ldg_nextExpiry6/Iso` `nextAmendmentNo` `ldg_pendingAmendmentNo` `ldg_amendmentStatus` `ldg_pendingDemandId/Type/Amount`。
* 场景步骤可加 `when:`（步骤级条件），如 `amendment-accepted` 中 MT787 只在 `ldg_amendmentStatus: PENDING` 时才发（已生效的延期修改不再要受益人答复）。
* kickoff（"触发"）会从台账选一笔合适的交易（角色/状态都能通过校验），并以**当前余额**为金额、当前到期日+1 年为延期日；可用 `?ref=` 指定交易、`?from=/?to=` 指定虚拟行。
  找不到时使用随机编号（你的系统应拒绝）。

## 5D. 跟单信用证台账（`LcLedgerService`，Stage 2）与跟单托收台账（`CollectionLedgerService`，Stage 3）

> **仅 MT**：Prowide `pw-iso20022 SRU2025` 中没有跟单信用证 / 托收的 MX 报文（ISO 20022 tsmt/tsin 未覆盖 MT7xx LC 与 Cat 4 托收的银行间流程），
> 所以 LC / 托收只支持 MT；kickoff 用 `format=MX` 时会提示"没有 MX 模板"。

台账接口：`GET /api/lcs[?status=&party=]`、`GET /api/lcs/{编号或交单/索偿编号}?includeRaw=true`、`POST /api/lcs/{ref}/parties`、`DELETE /api/lcs`（仅测试库）；
`/api/collections…` 同理。保函台账（`/api/undertakings`）不变。台账分发：报文类型属于哪个家族就进哪个台账；MT759/MT799/MT202/MT103/MT499 先按保函编号匹配，匹配不到再按 LC / 托收编号匹配。

### 信用证（MT700/701/705/707/708/710/711/720/721/730/732/734/740/742/744/747/750/752/754/756/759/799 + MT202/MT103）
* 编号解析：700/705/740/747 用 `:20:`；707 用 `:23:`（否则 `:20:`）；710/720/730 用 `:21:`；754/750/742 用 `:21:`=信用证号、`:20:`=交单/索偿编号；
  752/734/732/756 用 `:21:`=交单编号；744 先 `:21A:`(索偿编号) 再 `:21:`；MT202/103 用 `:21:`。
* 状态：`PRE_ADVISED → ISSUED → ADVISED/CONFIRMED → AMENDMENT_PENDING → AMENDED`，交单覆盖状态 `DOCS_PRESENTED / DISCREPANT`，结果 `PAID / REFUSED / DISCHARGED / AUTHORISED / ACCEPTED`，
  终态 `CANCELLED / EXPIRED`（营业日扫描）。可用余额 = 32B ×(1+39A 上浮%) − 已用 − 审单中金额。
* MT730 约定（72Z/79Z 第一行）：`/BENACC/` 受益人接受修改、`/BENREJ/<原因>` 拒绝、`/CONFIRMED/` 已加保兑（**模拟器约定**），其余为普通确认。
  MT707 `23S:CANCEL` 需 `/BENACC/` 后才变成 `CANCELLED`。MT752 `23:` `REMITTED/DEBIT` 视为已付款，其他代码视为授权。
* 业务拒绝码（虚拟行收报时回 MT759，`22D:DOCR`）：`BIZ-L01` 超额支用（超过 39A 上限）、`L02` 不许分批却部分支用、`L03` 过期交单、`L04` 重复交单/索偿编号、
  `L05` 状态不允许、`L06` 拒付超过 5 个银行工作日、`L07` 金额大于交单金额、`L09` 已付款/已偿付、`L10` 不可转让、`L11` 未要求保兑、`L12` 授权金额大于信用证、
  `L13` 重复 MT740、`L14` 新偿付金额低于已索偿、`L15` 转让到期日晚于原证、`L16` 审单中不能撤销、`L20` 未收到 MT740、`L21` 索偿超过偿付授权、`L22` 偿付授权过期后索偿，
  以及通用的 `U01/U02`（未知/重复编号）、`R01–R05`（角色）、`A01–A04`（修改：并发、序号、无待决修改、同时 BENACC/BENREJ）、`C01` 币种、`S03` 终态。
* SUT 收到的不一致报文（例如虚拟行超额交单、过期交单）不会被模拟器拦截，而是在台账记 `INCONSISTENT_MESSAGE` 事件（"你的系统应拒绝"）。

### 托收（MT400/410/412/416/420/422/430/450/455/456 + MT202/MT103；MT405 已从 SRU2025 删除，未实现）
* **托收指示约定**：SR2025 没有银行间"托收指示" FIN 报文（指示随单据寄送），模拟器用 **MT499 `:79:/COLLINST/<DP|DA|CLEAN|CASH>`** 表示，
  后续行 `/URC522/ /AMOUNT/<币种金额> /TENOR/<AT SIGHT|nn DAYS AFTER SIGHT> /MATURITY/<YYMMDD> /DRAWER/ /DRAWEE/ /DOCS/ /CHARGES/ /PROTEST/ /PARTIAL/YES`。`:20:` = 托收编号。
  跟单托收（DP/DA）的部分付款必须带 `/PARTIAL/YES`（URC 522 第 19 条），否则 `BIZ-K13`。`/PARTIAL/NO` 明确禁止。
* 状态：`SENT → ACKNOWLEDGED → ACCEPTED → PARTIALLY_PAID → PAID`、`NON_ACCEPTED / NON_PAID`、`AMENDED`、覆盖状态 `TRACER`、终态 `PAID / RETURNED`。
  MT422 `76:` 含 `/RETURNED/` = 单据已退回；MT430 `74:` 含 `/RETURN/` = 请求退单。
* 业务拒绝码（虚拟行回 **MT499** `79:`，Cat 4 无拒绝报文，模拟器约定）：`BIZ-K01` 超额付款/承兑、`K02` 重复托收编号、`K03` 金额缺失、`K04` D/A 缺少远期期限、
  `K05` MT410 金额与托收不符、`K06` 非 D/A 的承兑通知、`K07` 已全额付款、`K08` MT430 32a 与原金额不符、`K09` 重复承兑、`K10` 头寸/扣账大于已付、`K11` 未知类型、`K12` 无可调整金额、`K13` 未授权的部分付款、`K14`（仅当 `enforceCollectionMaturity=true`）到期前付款，
  另有 `R01–R03`（方向：MT410/412/416/422/400 必须由代收行发出，MT420/430 必须由托收行发出）。

### 保函补充约定（Stage 1）
* **发起行修改（SUT 为通知行）**：开立行 MT767 / tsrv.005 → 台账 `AMENDMENT_PENDING`；SUT 回 MT787 `23R:ACPT|REJT` / tsrv.008（可先回 MT768 / tsrv.019）。
  开立行收到 MT787 后回 MT759 `GENINFAD`（`issuer-followup`）；只收到 MT768 时不回复，60 秒后若仍待决则发 MT759 催询（kickoff 第二步，`fireWhen`）。
* **MT769**：按 SR2021+（Prowide SRU2025 字段名）`33B` = 本次减额/解除金额、`34B` = 减额后余额、`32B/32D` = 费用；`34B` 与台账不一致 → `BIZ-M03`。旧报文只有 32B 时仍按减额处理（兼容）。
* **索赔撤回**：SR2021 起无专用 MT，模拟器约定 MT759 `23H:OTHERFNC` + `45D` 含 `WITHDRAW`、`:21:` = 索赔编号（MX = tsrv.017）。
* **MT759 查询**：`45D` 含 `QUERY / PLEASE / KINDLY ADVISE / ? / CHASER` 视为查询；台账记录 `openQueryRef`，SUT 以 `:21:` = 查询 `:20:` 回复 MT759 即 `QUERY_ANSWERED`，
  未回复则 45 秒后发催询（kickoff 第二步，`fireWhen: { ldg_openQueryRef: "~." }`，已回复则自动 `SKIPPED`）。
* **`fireWhen`**（新）：kickoff / 反应步骤可写 `fireWhen: { 变量: 条件 }`，到期时按**当时的台账**重新判断，不满足则该步 `SKIPPED`（`GET /api/pending-responses?all=true` 可见），
  满足时模板也拿到最新台账变量。`when` 新增 `"!~正则"`（变量为空或不匹配）。
* **kickoff `select`**：`UND_DEMAND_PENDING / UND_AMENDMENT_PENDING / UND_ISSUER_TO_ADVISING / UND_OPEN_QUERY / UND_PARTIES / UND_REDUCIBLE`、
  `NEW_LC / LC_AMEND / LC_PRESENT / LC_PRESENT_EXPIRED / LC_PRESENTATION_PAYABLE / LC_PRESENTATION_PENDING / LC_PRESENTATION_REFUSED / LC_PRESENTATION_DISCREPANT / LC_CLAIM / LC_TRANSFER`、
  `NEW_COLL / COLL_PAYABLE / COLL_PAID / COLL_ACK …`；也可直接 `?ref=` 指定。
* **审核模式**同样适用于 LC / 托收：建议动作 LC = `ACK / CONFIRM / ACCEPT / REFUSE / PAY`（MT730、MT734、MT744、MT756+MT202 等），托收 = `ACK / ACCEPT / REFUSE / PAY`（MT410、MT412、MT416、MT400+MT202）。

## 5B. 网关真实度（近似 SWIFT FIN / FINplus，**均为模拟**）

| 项目 | 模拟器做法 |
|---|---|
| ACK / NAK | 与业务回复完全分离。MT：`{1:F21<LT><session><ISN>}{4:{177:YYMMDDHHMM}{451:0}{108:MUR}}` + 原报文；NAK 为 `{451:1}{405:SIMxxx}`（405 中是**模拟器自定义**错误码，非 SWIFT 码）。MX：`SimGatewayAck` XML（status/networkRef/错误） |
| 会话 / 序号 | 每个参与行一个 LT 会话 (`lt_session` 表)，**每次启动 session+1**；ISN（发）/OSN（收）6 位递增。block 1 中非 0 的 ISN 与期望不符时警告；`gateway.enforceIsn=true` 时 NAK SIM-S01 |
| MIR / MOR | MIR = 日期 + LT(12) + session(4) + ISN(6)；投递时生成 MOR。投递给收报行的是 **output 格式**：`{1:F01<收报LT><session><OSN>}{2:O<MT><HHMM><MIR><YYMMDD><HHMM><prio>}` |
| Trailer | 投递副本加 `{5:{CHK:<12 位 hex>}{TNG:}}`（CHK 是 SHA-256 截断，**不是**真实校验和；TNG = test & training，`gateway.testAndTrainingTrailer`）；PDE 副本加 `{PDE:}` |
| 提交格式 | block 1 必须 `F01`+12 位 LT+10 位数字（SIM-H01）；block 2 必须 input 格式 `I<MT><LT12><prio N/U/S>[monitoring 1/2/3][obsolescence 3n]`（SIM-H02），提交 output 格式 → SIM-H03；block 4 框架 → SIM-H04 |
| UETR | MT103/202/205 必须带 `{3:{121:UUIDv4}}`（SIM-H21，SR2018+ gpi 规则的近似）；格式错 SIM-H32。模拟器生成的 MT202 自动加 UETR |
| MUR / BizMsgIdr | `{108:}` 格式检查 (SIM-H31)；重复使用只警告 |
| 字段格式 | 每个字段按 Prowide `Field.validatorPattern()`（如 20=`16x`、32B=`<CUR><AMOUNT>15`、77U=`65z[$65z]0-149`）转换为正则校验长度/行数（SIM-F10），并检查 SWIFT x / z 字符集（SIM-C01，如 `É` `Ü` `@` 不允许）。`gateway.fieldFormatValidation=false` 可关 |
| PDE | 带 `{5:{PDE:}}` / AppHdr `PssblDplct=true` 的报文：按内容哈希查找原报文（`duplicateOf`），原报文已处理则业务层忽略 |
| 投递通知 | 发报方为 SUT 且 `deliveryNotifications: true`，或 block 2 monitoring = 2/3 时，回一条 **MT011 风格**的 DLV（`{4:{175:}{106:MIR}{108:}{175:}{107:MOR}}`，**近似**，未逐字段核对 FIN 系统报文手册）；N 优先级 + monitoring 1/3 给警告 |
| BAH | MX AppHdr：MsgDefIdr 必须与 Document 命名空间一致（SIM-H11）；`?from/?to` 与 AppHdr Fr/To 冲突 → SIM-H12；`gateway.requireAppHdr=true` 时无 AppHdr → SIM-H10；CreDt 在未来给警告；MX 回执带 `networkRef`（SIMSNF…）与传输 UUID |
| 重启 | 未投递（ACK 后、投递前）的报文在启动时重新投递；延迟中的场景回复（`pending_response` 表）在启动时恢复，已到期的立即发出；发送过程中断的回复以 PDE 方式重发 |

## 5C. 人工 / AI 审核模式（review mode）

对某个虚拟行打开审核模式后，它收到的报文**不再自动回复**，而是进入审核队列（`review_item` 表，状态 `PENDING_REVIEW`），并带上台账校验结论
（`ledgerVerdict` = APPLIED/REJECTED…、`ledgerErrors` = BIZ 码）和建议动作。你（或一个 AI agent）可以：

| action | 效果 |
|---|---|
| `ACK` | MT768 / tsrv.019 ACCP |
| `ACCEPT` | 开立 → MT768/tsrv.019；修改 → MT787 ACPT / tsrv.008 ACCP；tsrv.014 → tsrv.015 EXTD |
| `REFUSE` / `REJECT` | 修改 → MT787 REJT+`reasons`；索赔 → MT786 (77J = reasons) / tsrv.016 |
| `PAY` | 索赔 → MT202（UETR 自动生成）+ 2 秒后 MT769；MX → tsrv.019 DMND/ACCP（**MX 付款约定为近似**）；可带 `amount` |
| `EXTEND` | 延期修改 MT767 / tsrv.005，可带 `newExpiry` |
| `BUSINESS_REJECT` | MT759 / tsrv.019 REJT，原因 = 台账 BIZ 码 + `reasons` |
| `SCENARIO` | 按指定场景 `scenario` 自动处理 |
| `CUSTOM` | 原样发送 `raw`（完整报文或裸 block 4 + `mtType`），发报行/收报行自动补 |
| `DISMISS` | 不回复 |

开关：`config/participants.yml` 中 `reviewMode: true`，或运行时 `POST /api/participants/{id}/review-mode {"enabled":true}`（持久化，优先于 YAML）。

## 5. REST API 与 curl 示例

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/messages` | 提交原始 MT/MX。可选参数 `from`, `to`（参与行 ID 或 BIC）、`mtType`（仅提交裸 block 4 时）、`scenario`（强制本条使用某场景，也可用请求头 `X-Sim-Scenario`）。返回 202=ACK / 422=NAK |
| GET | `/api/messages` | 过滤：`to`, `from`, `ref`(txRef/relatedRef 模糊), `type`, `direction`(IN/OUT/INTERNAL/SYS), `sinceId`（增量轮询）, `limit`, `includeRaw` |
| GET | `/api/messages/{id}` / `/api/messages/{id}/raw` | 单条 / 原文 |
| DELETE | `/api/messages` | 清空日志 |
| GET | `/api/participants` | 参与行 |
| GET | `/api/scenarios` | 场景及激活状态 |
| POST | `/api/scenarios/{id}/activate` | 激活某场景（同组互斥） |
| POST | `/api/scenarios/{id}/trigger?format=MT\|MX&ref=&from=&to=` | 让虚拟行先发报给你的系统；默认从台账挑选交易并用其余额/到期日；body 可选 JSON 变量覆盖，如 `{"ref":"KEGTEE0001","amount":"1000,00","amountIso":"1000.00","demandId":"BEN001"}` |
| POST | `/api/admin/reload` | 重新加载 YAML |
| GET | `/api/undertakings?status=&party=` | 交易台账列表（party = 参与行 ID 或 BIC） |
| GET | `/api/undertakings/{ref}?includeRaw=` | 单笔：状态、参与方、修改、索赔、余额变动、**状态历史**、相关报文、模板上下文；不存在 → 404（附带无主事件） |
| POST | `/api/undertakings/{ref}/parties` | 补登记参与方 `{"participant":"APPLICANT_BANK","role":"APPLICANT"}`（ISSUER/ADVISING/SECOND_ADVISING/BENEFICIARY/APPLICANT） |
| DELETE | `/api/undertakings` | 清空台账（不删报文） |
| GET | `/api/review-queue?status=PENDING_REVIEW\|RESPONDED\|ALL&participant=` | 审核队列 |
| GET | `/api/review-queue/{id}` | 单条：报文、台账上下文、可用动作 |
| POST | `/api/review-queue/{id}/respond` | `{"action":"ACCEPT\|REFUSE\|PAY\|EXTEND\|BUSINESS_REJECT\|SCENARIO\|CUSTOM\|ACK\|DISMISS","reasons":[..],"amount":"..","newExpiry":"2028-01-31","raw":"..","mtType":"787","scenario":"..","note":".."}`；已处理 → 409 |
| GET/POST | `/api/participants/{id}/review-mode` | 查看/设置审核模式 `{"enabled":true}` |
| GET | `/api/pending-responses?all=` | 持久化的延迟回复（PENDING/FIRING/SENT/FAILED/SKIPPED） |
| GET | `/api/gateway/sessions` | 各参与行 LT 会话号、最后 ISN/OSN |
| GET/POST/DELETE | `/api/admin/business-date` | 查看 / 设置（并立即到期处理）/ 恢复模拟营业日 |
| POST | `/api/admin/expiry-sweep` | 立即做到期处理 |
| GET | `/api/samples`, `/api/samples/{name}` | 样例列表/内容 |

```bash
cd /workspace/swift-sim/samples
# 1) 你的系统开立保函给通知行 -> 立即得到 ACK，约 3 秒后收到通知行 MT768
curl -s -X POST --data-binary @mt760-issue-to-advising.fin http://localhost:8080/api/messages
curl -s "http://localhost:8080/api/messages?to=TSTAKEN0XXX&ref=KEGTEE2026000001"

# 2) MX 开立（带 AppHdr）-> tsrv.019 状态报告
curl -s -X POST -H 'Content-Type: application/xml' --data-binary @tsrv.001-issuance.xml http://localhost:8080/api/messages

# 3) 修改被拒绝：激活场景（或单条用 ?scenario=amendment-rejected）
curl -s -X POST http://localhost:8080/api/scenarios/amendment-rejected/activate
curl -s -X POST --data-binary @mt767-amendment.fin "http://localhost:8080/api/messages"

# 4) 你的系统作为开立行：让虚拟受益人银行发来"延期或付款"索赔 MT765 PAEX
curl -s -X POST "http://localhost:8080/api/scenarios/extend-or-pay/trigger?format=MT" -H 'Content-Type: application/json' -d '{"ref":"KEGTEE2026000001"}'

# 5) 只提交 block 4（模拟器补报头）
curl -s -X POST "http://localhost:8080/api/messages?from=SUT_BANK&to=ADVISING_BANK&mtType=769" \
     --data-binary $':20:KEREL0002\n:21:KEGTEE2026000001\n:30:261029\n:32B:USD50000,00'

# 6) 增量轮询发给你的系统的新报文
curl -s "http://localhost:8080/api/messages?to=SUT_BANK&sinceId=120&includeRaw=true"

# 7) 文件投递：放入 inbox，结果移到 inbox/processed 或 inbox/error（旁边生成 .result.txt）
cp mt785-non-extension.fin ../data/inbox/
#    MX 无 AppHdr 时可在文件名里带路由：xxx__SUT_BANK__ADVISING_BANK.xml
ls ../data/outbox/TSTAKEN0XXX/        # 发给你的系统的报文/ACK/NAK/投递通知
```

NAK 示例（`invalid-mt760-missing-fields.fin`）返回：
```json
{"status":"NAK","errors":["SIM-M01 mandatory field :40C: missing for MT760", "...",
 "SIM-F02 field :20: must not start/end with '/' or contain '//' (SWIFT error T26)",
 "SIM-F03 field :30: invalid date '261399' (expected YYMMDD)",
 "SIM-F04 field :32B: must be 3!a15d with decimal comma ..."],
 "ack":"{1:F21TSTAKEN0AXXX0000000000}{4:{177:2610291200}{451:1}{405:SIMM01}} ..."}
```
`SIM-xxx` 是**模拟器自定义**错误码，不是 SWIFT 官方错误码。

Webhook：给参与行配置 `webhookUrl` 后，投递给该行的每条报文（含 ACK/NAK/DLV）会 POST JSON：
`{"id","msgType","format","sender","receiver","txRef","status","raw"}`，结果记录在 `webhookStatus`。

## 6. 样例报文（`samples/`）

**样例 MT760（你的系统 → 通知行）** `samples/mt760-issue-to-advising.fin`：
```
{1:F01TSTAKEN0AXXX0000000000}{2:I760TSTCKEN0XXXXN}{3:{108:SAMPLE760001}}{4:
:15A:
:27:1/1
:22A:ISSU
:15B:
:20:KEGTEE2026000001
:30:261029
:22D:DGAR
:40C:URDG
:23B:FIXD
:31E:271031
:50:KENYA IMPORTS LTD (FICTIONAL)
NAIROBI KENYA
:52A:TSTAKEN0XXX
:59:/KE00TEST0000000000000001
EXAMPLE CONSTRUCTION CO (FICTIONAL)
MOMBASA KENYA
:56A:TSTCKEN0XXX
:32B:USD50000,00
:77U:WE HEREBY ISSUE OUR IRREVOCABLE DEMAND GUARANTEE NO
KEGTEE2026000001 IN FAVOUR OF THE BENEFICIARY FOR A MAXIMUM
AMOUNT OF USD 50,000.00 COVERING THE PERFORMANCE OBLIGATIONS
OF THE APPLICANT UNDER CONTRACT NO TEST-001.
THIS GUARANTEE IS SUBJECT TO URDG 758. SIMULATED - NO VALUE.
-}
```

**样例 tsrv.001（带 AppHdr）** `samples/tsrv.001-issuance.xml`：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<RequestPayload>
<AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">
  <Fr><FIId><FinInstnId><BICFI>TSTAKEN0XXX</BICFI></FinInstnId></FIId></Fr>
  <To><FIId><FinInstnId><BICFI>TSTCKEN0XXX</BICFI></FinInstnId></FIId></To>
  <BizMsgIdr>KEMX0001</BizMsgIdr>
  <MsgDefIdr>tsrv.001.001.01</MsgDefIdr>
  <CreDt>2026-10-29T08:00:00Z</CreDt>
</AppHdr>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:tsrv.001.001.01">
  <UdrtkgIssnc>
    <UdrtkgIssncDtls>
      <Id>KEGTEE2026MX0001</Id>
      <Nm>DGAR</Nm>
      <IssncTp>ISSU</IssncTp>
      <Applcnt><Nm>KENYA IMPORTS LTD (FICTIONAL)</Nm></Applcnt>
      <Issr><Nm>Your System Kenya Test Bank</Nm><Id><OrgId><AnyBIC>TSTAKEN0XXX</AnyBIC></OrgId></Id></Issr>
      <Bnfcry><Nm>EXAMPLE CONSTRUCTION CO (FICTIONAL)</Nm></Bnfcry>
      <DtOfIssnc>2026-10-29</DtOfIssnc>
      <AdvsgPty><Nm>Virtual Advising Bank Nairobi</Nm><Id><OrgId><AnyBIC>TSTCKEN0XXX</AnyBIC></OrgId></Id></AdvsgPty>
      <UdrtkgAmt><Amt Ccy="USD">50000.00</Amt></UdrtkgAmt>
      <XpryDtls><XpryTerms><DtTm><Dt>2027-10-31</Dt></DtTm></XpryTerms></XpryDtls>
      <GovncRulesAndLaw><RuleId><Cd>URDG</Cd></RuleId></GovncRulesAndLaw>
      <UdrtkgTermsAndConds><Txt>WE HEREBY ISSUE OUR IRREVOCABLE DEMAND GUARANTEE ...</Txt></UdrtkgTermsAndConds>
    </UdrtkgIssncDtls>
  </UdrtkgIssnc>
</Document>
</RequestPayload>
```

其它样例：`mt760-standby-to-advising.fin`(SBLC/ISPR)、`mt767-amendment.fin`、`mt765-demand-paym.fin`、`mt765-demand-paex.fin`、
`mt785-non-extension.fin`、`mt769-release.fin`、`mt759-to-applicant-bank.fin`、`mt768/786/787-*.fin`、
`tsrv.005/008/010/011/012/013(paym,paex)/014/015/016/017/019-*.xml`、`tsin.005-application.xml`，
以及 3 个故意出错的 `invalid-*`（用于 NAK 测试）。所有 MX 样例均已通过官方 XSD 校验。

### 新增样例（Stage 1–3，编号均为虚构）
* 你的系统作为**开证行**（信用证号 `KELC2026000001`）：`mt700-lc-issue-to-advising.fin`、`mt707-lc-amendment.fin`、`mt707-lc-cancellation.fin`、`mt740-lc-reimb-auth.fin`、
  `mt747-lc-reimb-amend.fin`、`mt756-lc-payment-to-presenter.fin`、`mt202-lc-cover-to-presenter.fin`、`mt734-lc-refusal.fin`、`mt732-lc-discharge.fin`、`mt752-lc-authorisation.fin`、
  `mt799-lc-free-format.fin`（交单编号 `KELCPRES0001` 需替换为 kickoff 产生的 `SIM…` 交单编号）。
* 你的系统作为**通知 / 指定 / 偿付 / 转让行**（`KELC2026000101` 需替换为 kickoff 返回的 `SIMLC…`）：`mt730-lc-ack-to-issuer.fin`、`mt730-lc-benacc-to-issuer.fin`、`mt730-lc-benrej-to-issuer.fin`、
  `mt730-lc-confirmed-to-issuer.fin`、`mt754-lc-presentation-to-issuer.fin`、`mt750-lc-discrepancy-to-issuer.fin`、`mt742-lc-claim-to-reimbursing.fin`、`mt720-lc-transfer.fin`、
  `mt202-lc-reimbursement-to-claimant.fin`、`mt744-lc-claim-reject.fin`。
* 你的系统作为**托收行**（`KECO2026000001` / `KECO2026000002`）：`mt499-coll-instruction-dp.fin`、`mt499-coll-instruction-da.fin`、`mt420-coll-tracer.fin`、`mt430-coll-amend.fin`、`mt430-coll-return.fin`。
* 你的系统作为**代收行**（`KECO2026000101` 需替换为 kickoff 返回的 `SIMCO…`，金额默认 USD 50000 时请同步修改）：`mt410-…`、`mt412-…`、`mt416-…`、`mt422-coll-fate-…`、`mt422-coll-returned-…`、
  `mt400-…`、`mt202-coll-cover-…`、`mt450-coll-cash-credit.fin`、`mt455-coll-adjustment.fin`、`mt456-coll-dishonour.fin`、`mt499-coll-free-format.fin`。
* 保函 Stage 1 回复：`mt787-response-to-issuer-amendment.fin`、`mt768-ack-issuer-amendment.fin`、`mt759-query-answer.fin`（`GBGTEE2026000777` / `QUERYREF0001` 需替换）、`mt769-reduction-to-advising.fin`（33B/34B）。

## 7. 内置场景（`config/scenarios.yml`）

| 场景 ID | 组 | 触发报文 → 虚拟行响应 | kickoff（虚拟行先发给你） |
|---|---|---|---|
| `happy-issuance` ★ | issuance | MT760/tsrv.001 → 通知行 2s 后 MT768 / tsrv.019 ACCP；tsin.005 → 开立行 tsrv.019 + tsrv.001 | 开立行 → 你：MT760 / tsrv.001 |
| `issuance-timeout` | issuance | 只有 ACK+投递，**永不回复**（测试超时/催办） | – |
| `issuance-slow` | issuance | 15 秒后才 MT768 / tsrv.019（用于测试重启后延迟回复恢复） | – |
| `issuance-duplicate` | issuance | MT768 发两次，第二次带 `{5:{PDE:}}` / AppHdr `PssblDplct=true` | – |
| `amendment-accepted` ★ | amendment | MT767 → MT768(1s) + MT787 `23R:ACPT`(3s)；tsrv.005 → tsrv.008 ACCP | – |
| `amendment-no-response` | amendment | MT767 → 只有 MT768，修改一直 AMENDMENT_PENDING（测试并发修改/超时） | – |
| `amendment-rejected` | amendment | MT767 → MT768 + MT787 `23R:REJT`+原因；tsrv.005 → tsrv.008 REJT | – |
| `demand-paid` ★ | demand | MT765 PAYM → MT768 + MT202(结算示意) + MT769(减额)；tsrv.013 PAYM → tsrv.019 | 受益人行 → 你：MT765 / tsrv.013 PAYM |
| `demand-refused` | demand | MT765 PAYM → MT768 + MT786 `77J` 不符点；tsrv.013 → tsrv.016 | 同上 |
| `demand-pending` | demand | MT765 → 只有 MT768，不付不拒（测试审单中、撤回、并发索赔） | – |
| `extend-or-pay` ★ | extend-or-pay | MT765 PAEX → MT768 + MT767 延期(31E=请求日期)；tsrv.013 PAEX → tsrv.019 + tsrv.005(NewXpryDtls)；tsrv.014 → 申请人行 tsrv.015 EXTD；MT759 → MT759 GENINFAD | 受益人行 → 你：MT765 / tsrv.013 PAEX |
| `non-extension` ★ | non-extension | MT785/tsrv.011/tsrv.010 → MT768 / tsrv.019 | 开立行 → 你：MT785 / tsrv.011 |
| `release-termination` ★ | release | MT769/tsrv.012/tsrv.017 → MT768 / tsrv.019 | 开立行 → 你：MT769 / tsrv.012 |
| `generic-ack` | (fallback) | 其他任何受支持报文 → MT768 / tsrv.019 | – |

★ = 默认激活。选择顺序：审核模式 → 业务拒绝（台账校验失败）→ 本条 `?scenario=` / `X-Sim-Scenario` → 组内激活场景（且 `when` 条件满足）→ fallback。
注意：`trigger` 会同时激活该场景（例如触发 `demand-refused` 后，后续索赔都会被拒，直到重新激活 `demand-paid`）。

### 7A. 保函补充场景（Stage 1）

| 场景 ID | 名称 | 组 | 方向 | SUT 发报后虚拟行的反应 | kickoff（虚拟行先发给 SUT） |
|---|---|---|---|---|---|
| `issuer-amendment` | 开证行发起修改(SUT为通知行) Issuer-initiated amendment to SUT (advising) | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt767-issuer-amend.fin / tsrv005-issuer-amend.xml<br>+60s ISSUING_BANK→SUT_BANK mt759-amend-chaser.fin (仅当 ldg_status=AMENDMENT_PENDING) |
| `issuer-followup` ★ | 开证行跟进(收到MT787/MT768后) Issuer follow-up after SUT MT787 / MT768 | issuer-followup | SUT→虚拟行 | MT787/tsrv.008→ISSUING_BANK: 2s mt759-noted.fin<br>MT768/tsrv.019→ISSUING_BANK: 不回复 | – |
| `issuer-followup-silent` | 开证行不跟进 Issuer follow-up - silent | issuer-followup | SUT→虚拟行 | MT787/tsrv.008→ISSUING_BANK: 不回复 | – |
| `issuer-reduction` | 开证行减额通知(MT769) Issuer reduction advice to SUT | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt769-reduction-kickoff.fin [select UND_REDUCIBLE] |
| `demand-withdrawal` | 索赔后撤回 Demand then withdrawal | – | 虚拟行→SUT | – | BENEFICIARY_BANK→SUT_BANK mt765-demand.fin / tsrv013-demand.xml<br>+20s BENEFICIARY_BANK→SUT_BANK mt759-withdrawal.fin / tsrv017-withdrawal.xml (仅当 ldg_pendingDemandId~.) |
| `demand-withdrawal-pending` | 撤回未决索赔 Withdraw a pending demand | – | 虚拟行→SUT | – | BENEFICIARY_BANK→SUT_BANK mt759-withdrawal.fin / tsrv017-withdrawal.xml [select UND_DEMAND_PENDING] |
| `query-chaser` | MT759查询及催询 MT759 query and chaser | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt759-query.fin [select UND_PARTIES]<br>+45s ISSUING_BANK→SUT_BANK mt759-chaser.fin (仅当 ldg_openQueryRef~.) |
| `query-answered` | 查询已答复 Query answered (no further message) | – | SUT→虚拟行 | MT759: 不回复 | – |

### 7B. 跟单信用证场景（Stage 2，仅 MT）

| 场景 ID | 名称 | 组 | 方向 | SUT 发报后虚拟行的反应 | kickoff（虚拟行先发给 SUT） |
|---|---|---|---|---|---|
| `lc-advice-ack` ★ | 信用证通知回执 LC advised - MT730 acknowledgement | lc-advice | SUT→虚拟行 | MT700/MT710/MT720→ADVISING_BANK/CONFIRMING_BANK/TRANSFEREE_BANK: 2s mt730-ack.fin | – |
| `lc-advice-confirmed` | 信用证保兑 LC advised and confirmed | lc-advice | SUT→虚拟行 | MT700→ADVISING_BANK/CONFIRMING_BANK: 2s mt730-ack.fin, 2s mt730-ack.fin | – |
| `lc-advice-timeout` | 信用证通知无回执 LC advice - no response | lc-advice | SUT→虚拟行 | MT700: 不回复 | – |
| `lc-advice-duplicate` | 信用证重复回执 LC advice - duplicate MT730 | lc-advice | SUT→虚拟行 | MT700→ADVISING_BANK/CONFIRMING_BANK: 1s mt730-ack.fin | – |
| `lc-amendment-accepted` ★ | 信用证修改被受益人接受 LC amendment accepted | lc-amendment | SUT→虚拟行 | MT707→ADVISING_BANK/CONFIRMING_BANK/BENEFICIARY_BANK: 1s mt730-ack.fin, 4s mt730-ack.fin | – |
| `lc-amendment-rejected` | 信用证修改被拒绝 LC amendment rejected | lc-amendment | SUT→虚拟行 | MT707→ADVISING_BANK/CONFIRMING_BANK/BENEFICIARY_BANK: 1s mt730-ack.fin, 4s mt730-ack.fin | – |
| `lc-amendment-pending` | 信用证修改待受益人答复 LC amendment - beneficiary silent | lc-amendment | SUT→虚拟行 | MT707→ADVISING_BANK/CONFIRMING_BANK/BENEFICIARY_BANK: 1s mt730-ack.fin | – |
| `lc-reimb-pays` ★ | 偿付行付款 Reimbursing bank honours claims | lc-reimbursement | SUT→虚拟行 | MT740/MT747→REIMBURSING_BANK: 不回复<br>MT742→REIMBURSING_BANK: 2s mt202-lc-settlement.fin, 3s mt799-free.fin | – |
| `lc-reimb-rejects` | 偿付行拒付索偿 Reimbursing bank rejects claims | lc-reimbursement | SUT→虚拟行 | MT740/MT747→REIMBURSING_BANK: 不回复<br>MT742→REIMBURSING_BANK: 2s mt744-claim-reject.fin, 3s mt799-free.fin | – |
| `lc-claim-to-reimbursing` | 议付行向偿付行索偿 Claim on the reimbursing bank (SUT issuer) | – | 虚拟行→SUT | – | ADVISING_BANK→REIMBURSING_BANK mt742-claim.fin [select LC_CLAIM] |
| `lc-presentation` | 交单(MT754)及催付 Presentation MT754 + chaser | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt754-presentation.fin [select LC_PRESENT]<br>+60s ADVISING_BANK→SUT_BANK mt799-chaser.fin (仅当 lc_openPresentations!0) |
| `lc-presentation-discrepant` | 不符点交单(MT750) Discrepant presentation MT750 | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt750-discrepancy.fin [select LC_PRESENT]<br>+60s ADVISING_BANK→SUT_BANK mt799-chaser.fin (仅当 lc_openPresentations!0) |
| `lc-presentation-overdraw` | 超额交单 Over-drawing presentation (beyond 39A) | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt754-presentation.fin [select LC_PRESENT] |
| `lc-presentation-expired` | 过期交单 Presentation under an expired LC | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt754-presentation.fin [select LC_PRESENT_EXPIRED] |
| `lc-presentation-duplicate` | 重复交单电文 Duplicate presentation (PDE) | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt754-presentation.fin [select LC_PRESENT] |
| `lc-issuer-pays` ★ | 开证行付款 Issuer pays the SUT presentation | lc-issuer | SUT→虚拟行 | MT754→ISSUING_BANK/CONFIRMING_BANK: 3s mt756-reimbursement.fin, 4s mt202-lc-settlement.fin<br>MT750→ISSUING_BANK/CONFIRMING_BANK: 3s mt752-authorisation.fin, 4s mt202-lc-settlement.fin | – |
| `lc-issuer-refuses` | 开证行拒付 Issuer refuses the SUT presentation | lc-issuer | SUT→虚拟行 | MT754/MT750→ISSUING_BANK/CONFIRMING_BANK: 3s mt734-refusal.fin | – |
| `lc-issuer-refuse-then-discharge` | 拒付后申请人放弃不符点 Refusal, then waiver + payment | lc-issuer | SUT→虚拟行 | MT754/MT750→ISSUING_BANK/CONFIRMING_BANK: 2s mt734-refusal.fin, 6s mt732-discharge.fin, 8s mt202-lc-settlement.fin | – |
| `lc-issuer-silent` | 开证行无回复 Issuer silent on presentation | lc-issuer | SUT→虚拟行 | MT754/MT750: 不回复 | – |
| `lc-advise` | 收到信用证(MT700) Inbound LC for advising | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-advise-confirm` | 收到信用证并要求保兑 Inbound LC - confirmation requested | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-advise-strict` | 不许分批的信用证 Inbound LC - partial drawings not allowed, no tolerance | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-advise-reimb` | 收到带偿付行的信用证 Inbound LC with a reimbursing bank | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-advise-transferable` | 收到可转让信用证 Inbound transferable LC | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-pre-advice` | 预通知后开证 Pre-advice MT705 then MT700 | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt705-pre-advice.fin<br>+10s ISSUING_BANK→SUT_BANK mt700-issue.fin |
| `lc-amend-inbound` | 收到信用证修改(MT707) Inbound LC amendment | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt707-amend.fin [select LC_AMEND] |
| `lc-cancel-inbound` | 收到信用证撤销请求 Inbound cancellation request (MT707 23S CANCEL) | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt707-cancel.fin [select LC_AMEND] |
| `lc-third-bank-advice` | 第二通知行(MT710) Advice of a third bank's LC | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt710-advice.fin [select NEW_LC] |
| `lc-transfer-inbound` | 信用证转让(MT720) Transferred LC to the SUT | – | 虚拟行→SUT | – | ISSUING_BANK→CONFIRMING_BANK mt700-issue.fin<br>+5s CONFIRMING_BANK→SUT_BANK mt720-transfer.fin |
| `lc-issuer-payment` | 开证行付款(MT756+MT202) Issuer pays an open SUT presentation | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt756-reimbursement.fin [select LC_PRESENTATION_PAYABLE]<br>+2s ISSUING_BANK→SUT_BANK mt202-lc-settlement.fin |
| `lc-issuer-refusal` | 开证行拒付(MT734) Issuer refuses an open SUT presentation | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt734-refusal.fin [select LC_PRESENTATION_PENDING] |
| `lc-issuer-discharge` | 开证行放单付款(MT732+MT202) Issuer discharges a refused presentation | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt732-discharge.fin [select LC_PRESENTATION_REFUSED]<br>+2s ISSUING_BANK→SUT_BANK mt202-lc-settlement.fin |
| `lc-issuer-authorise` | 开证行授权付款(MT752) Issuer authorises a discrepant SUT presentation | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt752-authorisation.fin [select LC_PRESENTATION_DISCREPANT]<br>+2s ISSUING_BANK→SUT_BANK mt202-lc-settlement.fin |
| `lc-reimb-auth-inbound` | 收到偿付授权(MT740) Inbound reimbursement authorisation | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt740-reimb-auth.fin [select NEW_LC] |
| `lc-reimb-amend-inbound` | 收到偿付授权修改(MT747) Inbound reimbursement amendment | – | 虚拟行→SUT | – | ISSUING_BANK→SUT_BANK mt747-reimb-amend.fin |
| `lc-claim-inbound` | 收到索偿(MT742) Inbound reimbursement claim | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt742-claim.fin [select LC_CLAIM] |
| `lc-claim-overclaim` | 超额索偿 Claim above the reimbursement authorisation | – | 虚拟行→SUT | – | ADVISING_BANK→SUT_BANK mt742-claim.fin [select LC_CLAIM] |

### 7C. 跟单托收场景（Stage 3，仅 MT）

| 场景 ID | 名称 | 组 | 方向 | SUT 发报后虚拟行的反应 | kickoff（虚拟行先发给 SUT） |
|---|---|---|---|---|---|
| `coll-paid` ★ | 托收付款(D/P付款;D/A承兑后到期付款) Collection paid | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 2s mt410-ack.fin, 5s mt400-payment.fin, 6s mt202-coll-cover.fin, 5s mt412-acceptance.fin, 10s mt400-payment.fin, 11s mt202-coll-cover.fin | – |
| `coll-accepted` | 托收承兑(D/A) Collection accepted, payment at maturity | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 2s mt410-ack.fin, 5s mt412-acceptance.fin | – |
| `coll-partial` | 托收部分付款 Collection partially paid | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 2s mt410-ack.fin, 5s mt400-payment.fin, 6s mt202-coll-cover.fin | – |
| `coll-non-payment` | 托收拒付/拒绝承兑 Non-payment / non-acceptance | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 2s mt410-ack.fin, 5s mt416-non-payment.fin | – |
| `coll-no-response` | 代收行无回复 Collecting bank silent | coll-remit | SUT→虚拟行 | MT499: 不回复 | – |
| `coll-duplicate-ack` | 重复托收回执 Duplicate MT410 | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 1s mt410-ack.fin | – |
| `coll-cash-dishonour` | 光票托收入账后退票 Cash letter credited, then dishonoured | coll-remit | SUT→虚拟行 | MT499→COLLECTING_BANK: 3s mt450-cash-credit.fin, 8s mt456-dishonour.fin | – |
| `coll-tracer-answer` ★ | 查询托收结果 Tracer answered (MT422) | coll-tracer | SUT→虚拟行 | MT420→COLLECTING_BANK: 3s mt422-fate.fin | – |
| `coll-tracer-returned` | 查询-单据已退回 Tracer - documents returned | coll-tracer | SUT→虚拟行 | MT420→COLLECTING_BANK: 3s mt422-fate.fin | – |
| `coll-tracer-silent` | 查询无回复 Tracer ignored | coll-tracer | SUT→虚拟行 | MT420: 不回复 | – |
| `coll-amend-noted` ★ | 托收修改确认 Collection amendment noted | coll-amend | SUT→虚拟行 | MT430→COLLECTING_BANK: 3s mt499-free.fin<br>MT430→COLLECTING_BANK: 3s mt422-fate.fin | – |
| `coll-remitter-instructs` ★ | 委托行处理拒付 Remitting bank instructs after non-payment | coll-remitter | SUT→虚拟行 | MT416→REMITTING_BANK: 3s mt430-return.fin<br>MT422→REMITTING_BANK: 3s mt430-amend.fin | – |
| `coll-remitter-silent` | 委托行无指示 Remitting bank silent | coll-remitter | SUT→虚拟行 | MT416/MT422: 不回复 | – |
| `coll-instruction-dp` | 收到即期付款交单托收(D/P) Inbound D/P collection + tracer | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt499-collinst.fin<br>+60s REMITTING_BANK→SUT_BANK mt420-tracer.fin (仅当 coll_milestone~^(SENT|INSTRUCTED|RECEIVED)$) |
| `coll-instruction-da` | 收到承兑交单托收(D/A) Inbound D/A collection + tracer | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt499-collinst.fin<br>+60s REMITTING_BANK→SUT_BANK mt420-tracer.fin (仅当 coll_milestone~^(SENT|INSTRUCTED|RECEIVED|ACKNOWLEDGED)$) |
| `coll-instruction-clean` | 收到光票托收 Inbound clean collection | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt499-collinst.fin |
| `coll-tracer-inbound` | 收到托收查询(MT420) Inbound tracer | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt420-tracer.fin |
| `coll-amend-inbound` | 收到托收修改(MT430) Inbound collection amendment | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt430-amend.fin |
| `coll-return-inbound` | 收到退单指示 Inbound instruction to return documents | – | 虚拟行→SUT | – | REMITTING_BANK→SUT_BANK mt430-return.fin |
| `coll-payment` | 代收行付款(MT400+MT202) Collecting bank pays the SUT | – | 虚拟行→SUT | – | COLLECTING_BANK→SUT_BANK mt400-payment.fin [select COLL_PAYABLE]<br>+2s COLLECTING_BANK→SUT_BANK mt202-coll-cover.fin (仅当 coll_paid~[1-9]) |
| `coll-adjustment-inbound` | 收到入账调整(MT455) Inbound credit adjustment | – | 虚拟行→SUT | – | COLLECTING_BANK→SUT_BANK mt455-adjustment.fin [select COLL_PAID] |
| `coll-dishonour-inbound` | 收到退票通知(MT456) Inbound advice of dishonour | – | 虚拟行→SUT | – | COLLECTING_BANK→SUT_BANK mt456-dishonour.fin [select COLL_PAID] |
| `trade-no-reply` | 贸易报文默认不回复 Trade messages - no automatic reply (fallback) | – | SUT→虚拟行 | MT700/MT701/MT705/MT707/MT708/MT710…: 不回复<br>MT202/MT103/MT759: 不回复 | – |

### 7D. 双向矩阵（谁先发报 → 用什么触发）

| 业务 | SUT 先发（直接 POST 报文；虚拟行按激活场景回复） | 虚拟行先发（`POST /api/scenarios/{id}/trigger`） |
|---|---|---|
| 保函开立 / 修改 / 索赔 / 延期或付款 / 不展期 / 解除 | 原有场景（issuance、amendment-*、demand-*、extend-or-pay…） | `happy-issuance`、`demand-paid`、`extend-or-pay`、`non-extension`、`release-termination` |
| 开立行发起修改（SUT 为通知行） | SUT 回 MT787 / MT768 → `issuer-followup` | `issuer-amendment`（MT / MX） |
| 独立减额 MT769 | SUT 作为开立行发 MT769（33B/34B 校验） | `issuer-reduction` |
| 索赔撤回 | SUT 发 MT759 撤回 / tsrv.017 → `release-termination` 回执 | `demand-withdrawal`、`demand-withdrawal-pending` |
| MT759 查询 / 催询 | SUT 回答（`query-answered` 静默吸收） | `query-chaser` |
| 信用证开立 / 通知 | SUT MT700 → `lc-advice-*` | `lc-advise`、`lc-advise-confirm`、`lc-advise-strict`、`lc-advise-reimb`、`lc-advise-transferable`、`lc-pre-advice`、`lc-third-bank-advice`、`lc-transfer-inbound` |
| 信用证修改 / 撤销 | SUT MT707 → `lc-amendment-*` | `lc-amend-inbound`、`lc-cancel-inbound` |
| 交单 / 付款 / 拒付 | SUT MT754/MT750 → `lc-issuer-*`；SUT 付款 MT756+MT202 / 拒付 MT734 / MT732 / MT752 | `lc-presentation`、`lc-presentation-discrepant`、`-overdraw`、`-expired`、`-duplicate`；`lc-issuer-payment / -refusal / -discharge / -authorise` |
| 偿付 | SUT MT740/MT747 → `lc-reimb-*`；SUT 作为索偿行 MT742 | `lc-reimb-auth-inbound`、`lc-reimb-amend-inbound`、`lc-claim-inbound`、`lc-claim-overclaim`、`lc-claim-to-reimbursing` |
| 托收（SUT 为托收行） | SUT MT499 /COLLINST/ → `coll-paid / -accepted / -partial / -non-payment / -no-response / -duplicate-ack / -cash-dishonour`；MT420 → `coll-tracer-*`；MT430 → `coll-amend-noted` | `coll-payment`、`coll-adjustment-inbound`、`coll-dishonour-inbound` |
| 托收（SUT 为代收行） | SUT MT410/412/400/416/422 → `coll-remitter-*` | `coll-instruction-dp / -da / -clean`、`coll-tracer-inbound`、`coll-amend-inbound`、`coll-return-inbound` |

未在任何场景中出现的 LC / 托收报文由 `trade-no-reply`（fallback，位于 `generic-ack` 之前）记账但不回复。

```bash
# 例：虚拟开证行开 LC 给你（返回 ref=SIMLC…），你回 MT730 后，再让议付行交单
curl -s -XPOST localhost:8080/api/scenarios/lc-advise/trigger -H 'Content-Type: application/json' -d '{}'
curl -s -XPOST "localhost:8080/api/scenarios/lc-presentation/trigger?ref=SIMLC2610300001" -H 'Content-Type: application/json' -d '{}'
# 例：你作为托收行发 D/P 托收指示（样例），代收行回 MT410 + MT400 + MT202
curl -s -XPOST localhost:8080/api/messages --data-binary @samples/mt499-coll-instruction-dp.fin
# 例：单条报文改用"拒付"反应
curl -s -XPOST "localhost:8080/api/messages?scenario=coll-non-payment" --data-binary @samples/mt499-coll-instruction-dp.fin
# 例：开证行对你通知的保函发起修改；查询 + 45 秒催询
curl -s -XPOST "localhost:8080/api/scenarios/issuer-amendment/trigger?ref=<保函编号>" -H 'Content-Type: application/json' -d '{}'
curl -s -XPOST "localhost:8080/api/scenarios/query-chaser/trigger?ref=<保函编号>&from=ADVISING_BANK" -H 'Content-Type: application/json' -d '{}'
```

### 如何新增场景
```yaml
  - id: amendment-slow-reject
    name: 修改 10 秒后被拒
    group: amendment                 # 与 amendment-accepted 互斥
    reactions:
      - messageTypes: [MT767, tsrv.005]   # 也可写 "*"
        to: [ADVISING_BANK]               # 收到该报文的虚拟行；省略 = 任意虚拟行
        when: { f22A: "~ISU" }            # 可选：变量等于 / "~正则" / "!不等于"
        respond:
          - delaySeconds: 10
            from: $receiver               # 默认；也可写参与行 ID
            to: $sender                   # 默认
            mt: mt787-response.fin        # MT 入站时使用
            mx: tsrv008-response.xml      # MX 入站时使用（缺哪个就跳过该格式）
            vars: { status: "REJT\nAMOUNT NOT ACCEPTABLE", mxStatus: REJT }
            repeat: 1
            possibleDuplicate: false
            note: "可选：写入日志的备注"
    kickoff:                              # 可选：UI"触发"按钮
      - { from: ISSUING_BANK, to: SUT_BANK, mt: mt767-extension.fin }
```
然后 `curl -X POST localhost:8080/api/admin/reload`。

### 模板与变量（`config/templates/`）
* MT 模板只写 block 4 字段行（`#` 开头为注释），文件名必须以 `mtNNN` 开头（决定报文类型），引擎自动加 `{1:}{2:}{3:{108:}}` 报头；
  渲染后空行会被删除（便于用 `${!extra|''}` 做可选字段）。
* MX 模板只写 `<Document>`，引擎用 Prowide 加 AppHdr。
* 语法：`${name}`、`${a|b|'默认值'}`（取第一个非空）、`${!name}`（MX 中不做 XML 转义）。
* 常用变量：`ref`(保函编号) `newRef` `today6` `todayIso` `nowIso` `currency` `amount`(SWIFT 逗号格式) `amountIso`
  `amendmentNo` `newExpiry6/newExpiryIso` `expiry6/expiryIso` `demandId` `demandType` `issuerBic` `reason`
  `sender` `receiver` `fromBic` `fromName` `toBic` `toName` `inboundType` `bic_<参与行ID>`；
  MT 入站的每个字段 `f<tag>`（如 `f20`, `f32B`, `f52A`），MX 入站的每个叶子元素 `x_<元素名>`。
* 所有回报同样经过网关校验；若模板写错会被 NAK 并显示在日志中。

## 8. 端到端测试结果

**测试不要跑在正式实例上。** 用临时实例（端口 8090、数据目录 `/tmp/swift-sim-e2e`，与 `./data` 完全隔离）：
```bash
tests/temp-instance.sh start          # 启动临时实例（wipe = 停止并删除临时数据）
python3 tests/e2e.py                  # 全量（默认 SIM_URL=http://127.0.0.1:8090、管理端 8091、AI 8092，重启阶段只重启临时实例）
python3 tests/e2e_trade.py            # 只跑 Stage 1-3（G-J）
tests/temp-instance.sh wipe
```
可用环境变量 `SIM_URL / SIM_DATA_DIR / SIM_STOP_CMD / SIM_START_CMD` 覆盖。
最近一次运行（2026-10-29 PDT）：**352/352 通过**（原有 148 + Stage 1–3 的 144 + 管理端/AI API/MCP 的 60），约 8 分钟。临时实例同时开 8091（管理端）/ 8092（AI API），凭据文件在 `/tmp/swift-sim-e2e/secrets/`。覆盖：

* **A. SUT 作为开立行 (MT)**：开立→通知 (ADVISED)、SBLC、重复编号 BIZ-U02、未知编号修改 BIZ-U01、修改接受（金额/到期日生效）、修改序号错 BIZ-A02、
  修改未答复 (AMENDMENT_PENDING) + 并发修改 BIZ-A01、角色错误 BIZ-R03/BIZ-R02、不展期通知、受益人行索赔 + SUT 拒付 (REFUSED)、减额 (REDUCED)、
  减额超余额 BIZ-M01、币种不符 BIZ-C01、全额解除 (RELEASED)、解除后报文 BIZ-S03、发给非参与方的 MT759。
* **B. SUT 作为通知行 (MT)**：虚拟开立行开立、SUT MT768→ADVISED、索赔付款（MT202+MT769，余额只扣一次）、超余额 BIZ-D01、币种 BIZ-C01、拒付、
  重复索赔号 BIZ-D02、审单中 + 并发索赔 BIZ-D03、tsrv.017 撤回、延期或付款→延期 (EXTENDED)、无未决修改的 MT787 BIZ-A03、MT759、tsrv.010、
  kickoff 使用台账到期日/余额、kickoff 自动选交易、解除后索赔 BIZ-S03、营业日推后→EXPIRED、到期后索赔 BIZ-S02。
* **C. MX**：tsrv.001/005(接受/拒绝)/011/013(PAYM/PAEX)/014/015/016/017/019/012/008、tsin.005，参与方补登记、延期或付款全流程、
  角色错误 tsrv.015 → REJT、终止后修改 REJT、PDE/PssblDplct 副本被台账忽略、超时不回复。
* **D. 网关**：5 类旧 NAK + 字符集 SIM-C01、字段格式 SIM-F10、MT202 缺 UETR SIM-H21、提交 output block 2 SIM-H03、AppHdr MsgDefIdr 不符 SIM-H11、
  ?from 与 AppHdr 冲突 SIM-H12；ACK `{451:0}`/ISN 递增/MIR；output 格式 + MOR + `{5:{CHK:}{TNG:}}`；DLV 引用 MIR；PDE 重发被忽略 / 无原报文正常处理；
  无 AppHdr MX；会话 API；文件投递；outbox；`GET ?to=`；台账列表与 404。
* **E. 审核队列**：审核模式下不自动回复、ACCEPT、重复处理 409、REFUSE+原因（MT787 REJT）、BUSINESS_REJECT（BIZ-A02）、CUSTOM 原文、MX 索赔 PAY。
* **G. 保函补充**：开立行发起修改（MT767 / tsrv.005 → AMENDMENT_PENDING）、SUT MT768 时开立行静默、SUT MT787 后开立行 MT759 跟进、独立减额 MT769（33B/34B）、
  SUT 发 MT769 34B 不一致 BIZ-M03、索赔 + 20 秒后 MT759 撤回、tsrv.017 撤回未决索赔、MT759 查询 → SUT 回答 → QUERY_ANSWERED。
* **K. 审计修复**：非法日期 `260231`、币种 `ZZZ`、ISSU+STBY 缺 49、ISCO 缺序列 C 均 NAK；ISCO 记两笔义务；pacs.009 部分付款后补足；MT761 续报写入条款。
* **H. 跟单信用证**：SUT 开证 → MT730、重复证号、修改 /BENACC/ 生效、修改待决 + 并发修改 BIZ-A01 / 序号 BIZ-A0x、MT740 + 重复 BIZ-L13、索偿行→偿付行 MT742 → MT202 + MT799、
  议付行交单 MT754 → SUT 付款 MT756+MT202（PAID）、重复付款 BIZ-L09、超额交单（INCONSISTENT BIZ-L01）、PDE 重复交单只记一次、MT750 不符点 → SUT MT734 拒付；
  SUT 为通知/指定行：MT730、MT707 + /BENACC/、MT754 → 开证行 MT756+MT202、超额支用 BIZ-L01、MT750 → MT734、重复交单号 BIZ-L04、开证行静默 + kickoff 付款、
  不许分批 BIZ-L02、过期交单 BIZ-L03 + EXPIRED、/CONFIRMED/、无 MT740 索偿 BIZ-L20、MT720 转让 + 不可转让 BIZ-L10、MT705→MT700、MT700(内部)+MT720、MT710、
  SUT 为偿付行：MT740、MT742 → SUT MT202 偿付、超额索偿、MT747；审核模式 ACK → MT730；`GET /api/lcs`。
* **I. 跟单托收**：D/P 付款（MT410+MT400+MT202 → PAID）、重复编号 BIZ-K02、D/A（MT412 承兑后付款）、D/A 缺期限 BIZ-K04、拒付 MT416 → SUT MT430 退单 → MT422 /RETURNED/、
  部分付款、代收行静默 + MT420 查询 → MT422、MT430 修改、kickoff 付款 / MT455；SUT 为代收行：MT410、超额付款 BIZ-K01、MT416 → 托收行 MT430 /RETURN/、MT422 /RETURNED/、
  MT412+MT400+MT202、D/P 上的 MT412 BIZ-K06、MT420 / MT430 kickoff、MT422 → MT430；审核模式 PAY → MT400+MT202；`GET /api/collections`。
* **J. 延迟催询 (`fireWhen`)**：未回答的查询 → 催询已发；已回答的查询 / 已答复的修改 → 催询 `SKIPPED`；延迟撤回已生效。
* **O. 管理端 / AI API / MCP**：端口隔离（8080 无 /admin、/ai）、登录与错误密码、凭据文件 600 且接口不回显密钥、CSRF 头、报文过滤（NAK / 业务拒绝）与详情（字段、校验代码）、三类台账列表/详情/核对、validate dry-run（OK 且不发送、不占 ISN/OSN、台账回滚；WOULD_NAK；BIZ-U01 业务拒绝；block 4 包装）、以任意机构发报（channel=ADMIN）、日志追踪、审计、在线备份、导出、清理（两阶段确认码、受保护编号跳过、ISN/OSN 不变）、审批草稿预览（不发送）+ operator key 处理、只读 key 403 + 审计、游标轮询、规则代码/状态机、MCP initialize/tools/list/tools/call/权限。
* **F. 重启**：延迟中的回复（15 秒）在重启后发出、ACK 后未投递的报文重启后重新投递、台账保留、LT 会话号 +1。

## 9. 已知限制 / TODO（请勿当作 SWIFT 规则依据）

* **不是完整的 SWIFT 网络校验**：必填字段来自 Prowide SRU2025 结构，字段格式/长度/字符集来自 Prowide `validatorPattern`，另有 MT765 两条规则和 MT760 的 22A/49/序列 C 条件规则；
  未实现可选序列内部必填、绝大多数 NVR/用法规则 (C/D/E/T 错误码)。完整校验需 SWIFT 官方 NVR 或商用校验器。日期用严格日历；币种用内置 ISO 4217 表（不是 SWIFT 的实时代码表）。
* **网关报文均为近似**：ACK/NAK 的 405 错误码是模拟器自定义 `SIMxxx`（真实 FIN 为 `T/H/M…` 错误码，未实现）；DLV 仅"仿 MT011"结构（字段未逐项核对 FIN 系统报文手册），
  MT010/MT019 未生成；CHK 不是真实校验和；会话为每次启动 +1，不模拟 login/select/quit、窗口、序号缺口恢复；MX 回执不是 FINplus/InterAct 的 xsys 报文，无签名 / LAU / PKI。
* TODO 代码值/语义需对照 SWIFT UHB (SR2021+) 核实：
  * MT787 `23R` 用 `ACPT/REJT`；MT765 `22G` 用 `PAYM/PAEX`；MT767 `22A` 用 `ISUA`；MT760 `22A` 用 `ACNA/ADVI/ICCA/ISCO/ISSU`；
  * MT767 中 32B = 增额、33B = 减额（台账按此记账）；MT769 现按 Prowide SRU2025 字段名：33B = 减额/解除金额、34B = 余额、32B/32D = 费用（旧报文仅有 32B 时仍视为减额；若与一笔已付但未通知减额的索赔金额相同，则视为该付款的减额通知，不再重复扣减），39C 未处理；
  * MT785 中 31C/31E 的含义；MT759 `23H` 的代码（`OTHERFNC`/`GENINFAD`/`REQISSUE` 仅为占位）；
  * **业务拒绝的格式**：MT 侧用 MT759 `23H:OTHERFNC` + 45D 原因、MX 侧用 tsrv.019 `Sts=REJT` —— 这是**模拟器约定**，真实银行间如何拒绝需核实；
  * tsrv.019 `StsCtgy` 白名单为 `ISSU/DMND/TERM/NEXT/APPL/GENR`（未知值 NAK `SIM-X07`），仍需对照 ISO 20022 External Code Sets；`DMND/ACCP` 只表示索赔已受理（`DEMAND_ACCEPTED`），付款由 MT202/MT103 或 pacs.008/pacs.009 完成；
  * "延期或付款"期间开立行发出的延期修改**直接生效**、tsrv.005 `BnfcryCnsntReqInd=false` 直接生效 —— 简化处理（URDG 下修改原则上需受益人同意）；
  * MT768 / tsrv.019 ACCP 由通知行发给开立行即视为"已通知 (ADVISED)" —— 简化；
  * N 优先级 + monitoring 1/3 只给警告；UETR 要求仅对 MT103/202/205。
* tsrv.008 v01 没有拒绝原因字段，所以 MX 的修改拒绝只有状态码。
* 台账简化：保函编号全局唯一（真实世界中编号只在开立行内唯一）；参与方角色从报文字段推断，线下关系需用 API 补登记。营业日排除周末，并可在 `ledger.holidays` 追加共享的非营业日（没有按银行的假日表，也没有时区/截止时间）。
* 台账更新与报文 `DELIVERED` 在同一事务提交。重启只重放仍为 `ACK` 的报文；若该报文号已经记过台账事件，则只补投递、不再改台账。文件 outbox 和 webhook 仍在事务之外。
* MX 贸易报文仅支持 `.001.01`；资金腿另支持 `pacs.008.001.08` 与 `pacs.009.001.08`（结构检查，不是完整 XSD）。AppHdr 只接受 head.001.001.02（其它命名空间 NAK `SIM-X06`）。一条报文里出现两个 `<Document>` 会被拒绝。日志/台账库无清理策略。
* 无鉴权、无 HTTPS；单实例（只监听 127.0.0.1）。8080 不鉴权是故意的。
* 未覆盖：MT 798 企业报文。MT701/708/711/721 续页仍只挂关联；MT761/775 会把叙述追加到保函 `additionalTerms`。
* **跟单信用证 / 托收（Stage 2/3）的已知限制与 SR2025 不确定点**（请对照 SWIFT UHB 核实）：
  * 只支持 MT（Prowide 无 LC / 托收 MX）；MT701/708/711/721 只做续页关联，不合并字段；MT405 在 SRU2025 中已删除，未实现。
  * **托收指示用 MT499 `/COLLINST/`、托收业务拒绝用 MT499、MT730 的 `/CONFIRMED/`、LC 业务拒绝用 MT759 `22D:DOCR`** 均为模拟器约定；`/BENACC/` `/BENREJ/` 为 SR2018+ 常用写法但仍需核实。
  * MT455 的 33C/33D 按"33C = 贷记调整、33D = 借记调整"理解（Prowide 格式为 日期+币种+金额）；MT744 `73R/73S` 只用 `OTHR/…` 代码；MT416 未使用 23E；MT707 `22A` 只用 `ISSU`；
    MT430 32a/33a 只支持 A/K 选项中的 A；MT752 只认 `REMITTED/DEBIT` 为付款。
  * 偿付授权金额含 39A 上浮（MT740 32B × (1+上浮%)）；MT747 34B 由模拟器按"原授权 + 10%"生成。MT742 与交单的关联：`:72Z:` 的 `/PRESREF/<交单号>`（或索偿 `:20:` 与交单 `:20:` 相同）显式关联；否则仅当同一交单行恰好有一笔金额等于 32B 本金、且尚未关联的交单时才自动关联。没有关联交单时，占用按 **32B 本金**而不是 34a（含费用）记。另一笔交单不会仅因同一 BIC 就吞掉本笔占用。
  * 延期/承兑交单（MT754 用 32B 而非 32A）可在 `:72Z:` 写 `/MATURITY/YYMMDD`。到期前的付款仍入账，并在事件里注明早于到期日。32A 表示指定行已垫付/议付（`nominatedBankPaid`）。
  * 银行工作日排除周末，并排除 `ledger.holidays`；L06 "5 个银行工作日"按这个日历计算。
  * UI 页面仍以保函台账为主，LC / 托收台账请用 `/api/lcs`、`/api/collections`（或台账 JSON）查看。
