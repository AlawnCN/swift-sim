# 支付应用文件夹传输

网关（默认 8080）、中文管理端（默认 8081）和 AI API（默认 8082）是**同一个进程**里的三个端口。文件传输跑在网关进程里，报文仍经过原来的校验、ACK/NAK、台账和场景引擎。管理端只负责查看和修改传输模式、路径和状态，不单独收发文件，也没有 SFTP 服务或账号。

支付应用不需要改成 HTTP 客户端。把下面的目录放在本机，或挂到模拟器能读写的共享盘上即可。

## 方向（全部按支付应用的视角）

根目录由 `sim.transport.file.root` 指定，示例 `/path/to/kenex`。不要把某台服务器或某个用户的家目录写进配置。

```
/path/to/kenex/
├── outgoing/
│   ├── mt/                 # 支付应用发出的 MT。模拟器的输入。扩展名 .txt。应用自己不清理
│   └── mx/                 # 支付应用发出的 MX。模拟器的输入。扩展名 .xml。应用自己不清理
├── incoming/
│   ├── mt/                 # 模拟器交给支付应用的 MT。模拟器的输出。模拟器不清理
│   └── mx/                 # 模拟器交给支付应用的 MX。模拟器的输出。模拟器不清理
├── temp/
│   ├── outgoing/
│   │   ├── mt/             # 模拟器成功消费后的 MT 原文件（移动归档，不是删除）
│   │   └── mx/             # 模拟器成功消费后的 MX 原文件。分目录是为了避免同名冲突
│   └── incoming/           # 只属于支付应用。应用读完 incoming 后自己挪到这里
└── .swiftsim/              # 只属于模拟器，并且只放还没完成或失败的文件
    ├── inflight/{mt,mx}    # 已领取、尚未提交完
    └── error/{mt,mx}       # 失败隔离、回声抑制。不是成功归档
```

`temp/outgoing` 是模拟器的收报清理 / 日志归档目录，也是**唯一**的成功归档。不要再默认另建一套成功归档根（例如不再使用 `.swiftsim/processed`）。子目录 `mt` 和 `mx` 把两种原文分开，同名文件不会撞在一起；同一目录里若已经有同名文件，模拟器改用 `名字-2`、`名字-3`，不覆盖、不删除已有内容。

用户原文里的 `temp/incomping` 就是这棵树里的 `temp/incoming`。

模拟器**不会**创建、监听、读取、移动或删除 `temp/incoming`。它写到 `incoming` 之后也不负责把文件清走。支付应用写到 `outgoing` 之后自己不清理，完全由模拟器在处理之后**移动**到 `temp/outgoing`。清理是移动归档，不是删除。

失败和领取中的文件不进 `temp/outgoing`：还在处理的在 `.swiftsim/inflight`，校验失败、编码无法识别、以及被抑制的回声在 `.swiftsim/error`（旁边有 `.reason.txt`）。`sim.transport.file.state-dir` 只改这两处的位置，默认 `<root>/.swiftsim`，不能和 `outgoing`、`incoming`、`temp/outgoing`、`temp/incoming` 重叠。

现有文件名例子：

* `outgoing/mt/MT700PYHNHFCY3PUDF7S.txt`
* `outgoing/mx/MXPYHNHE327RFJ24G.xml`

模拟器写出的文件沿用同样的习惯：`MT` + 三位类型（或 `ACK` / `NAK` / `DLV`）+ 16 位大写字母数字 + `.txt`，MX 为 `MX` + 16 位 + `.xml`。名字冲突时换一个新名字，不覆盖。

## 支付应用怎么写、怎么读

编码用 UTF-8（无 BOM）。MT 传统上是 ASCII；模拟器按 UTF-8 读写，也接受带 BOM 的 UTF-8 和 UTF-16。非法字节不会送进网关，文件会进 `.swiftsim/error` 并写明原因。

写 outgoing 时请先写临时文件，写完并刷盘后再原子改名。模拟器不读以 `.` 开头的文件，也不读 `.tmp`、`.partial`、`.filepart`、`.swp` 等临时后缀。除此之外，文件的大小和修改时间还要连续 `sim.transport.file.stable-ms`（默认 1000 毫秒）不变才会被领取。`stable-ms=0` 时，第一次看到的非空文件就会领取，这时更要靠原子改名，避免交出半截文件。

```bash
ROOT=/path/to/kenex
# 支付应用准备发报目录，以及它自己的已读归档 temp/incoming。
# 模拟器在 FILE/BOTH 下创建 incoming 与 temp/outgoing，并在成功后把原文移入 temp/outgoing。
mkdir -p "$ROOT/outgoing/mt" "$ROOT/outgoing/mx" "$ROOT/temp/incoming"

# 写出一笔 MT。临时名以点开头，模拟器不会把它当成报文。
tmp="$ROOT/outgoing/mt/.MT700PYHNHFCY3PUDF7S.txt.partial"
cat > "$tmp" <<'EOF'
{1:F01TSTAKEN0AXXX0000000000}{2:I700TSTCKEN0XXXXN}{4:
:20:YOURREF
-}
EOF
# 生产代码里这里应 fsync。然后原子改名：
mv "$tmp" "$ROOT/outgoing/mt/MT700PYHNHFCY3PUDF7S.txt"

# 读模拟器交回来的报文（ACK、投递通知、场景回复、对方主动发来的报文）
ls "$ROOT/incoming/mt" "$ROOT/incoming/mx"

# 处理完由支付应用自己挪走。模拟器不会管这个目录。
mv "$ROOT/incoming/mt/"MT*.txt "$ROOT/temp/incoming/" 2>/dev/null || true
```

MX 同样：临时文件放在 `outgoing/mx`，改名为 `MX` + 编号 + `.xml`。

## 模式

| `sim.transport.mode` | 含义 |
|---|---|
| `HTTP`（默认） | 保持原有 REST、`data/inbox`、`data/outbox`。不创建、不扫描 kenex |
| `FILE` | 从 `outgoing` 收。交给支付应用的副本写到 `incoming`。HTTP API 仍可用来管理和排障 |
| `BOTH` | 两种都收。自动回复跟着进来的通道，不两边各送一份 |

路由：

* 从 `outgoing` 进来的报文（channel `FOLDER`）及其 ACK、投递通知、场景回复、审核回复，写到 `incoming`。成功消费的原文件移动到 `temp/outgoing/mt` 或 `temp/outgoing/mx`。
* HTTP / 管理端 / 原来的 `data/inbox` 进来的报文，在 `BOTH` 下**不**再写一份到 `incoming`。
* 没有入向报文的主动发报（场景 kickoff）使用 `sim.transport.originate`。留空时：`FILE` 模式为 `FILE`，`HTTP` 和 `BOTH` 为 `HTTP`。单次可以加 `transport=FILE` 或 `transport=HTTP`（`POST /api/scenarios/{id}/trigger`、`POST /api/messages`、管理端发报）。
* 模拟器不会把 `incoming` 或 `temp/incoming` 里的文件再读回去。如果有人把模拟器刚写出的字节原样拷回 `outgoing`，该文件会当作回声放进 `.swiftsim/error`，不进入 `temp/outgoing`，也不再提交。
* `data/outbox/<BIC>` 仍是模拟器自己的报文归档，不是支付应用的收件箱，也不是 `temp/outgoing`。不要让支付应用同时消费 `data/outbox` 和 `incoming`。
* 文件夹进来的报文仍会记在数据库里，管理端和 `GET /api/messages` 能看到。这是控制面记录。`BOTH` 时不要把“轮询 API”和“读 incoming”当成同一笔业务的两个投递口。

## 启动

```bash
./run.sh --sim.transport.mode=BOTH --sim.transport.file.root=/path/to/kenex
```

只走文件夹：

```bash
./run.sh --sim.transport.mode=FILE --sim.transport.file.root=/path/to/kenex --sim.transport.originate=FILE
```

环境变量（Spring Boot 宽松绑定）示例：`SIM_TRANSPORT_MODE=BOTH`、`SIM_TRANSPORT_FILE_ROOT=/path/to/kenex`、`SIM_TRANSPORT_ORIGINATE=FILE`、`SIM_TRANSPORT_FILE_STABLE_MS=1000`、`SIM_TRANSPORT_FILE_POLL_MS=2000`。

管理端「文件传输」页可以改这些值。保存后写入 `data/file-transport.properties`。**下次启动时，这个文件里出现的键会覆盖命令行和 application.yml 里的同名项。** 轮询间隔在下一次轮询生效。关闭进程时轮询停止，不再领取新文件。

其它键：

| 键 | 默认 | 作用 |
|---|---|---|
| `sim.transport.file.state-dir` | `<root>/.swiftsim` | 只放 inflight 和 error。成功归档固定是 `<root>/temp/outgoing`，不受这项影响。不能落在 outgoing、incoming、temp/outgoing 或 temp/incoming 里面 |
| `sim.transport.file.poll-ms` | 2000 | 轮询间隔，最小 200 |
| `sim.transport.file.stable-ms` | 1000 | 大小和 mtime 要保持不变这么久 |
| `sim.transport.file.max-bytes` | 2000000 | 更大的文件移入 error，不送进网关 |

根目录至少要有两级（拒绝 `/` 或 `/tmp` 这种过浅的路径）。根或交换目录如果是符号链接，模拟器拒绝跟随，并在管理端和日志里说明。单个报文文件若是符号链接，或真实路径跑出 outgoing 目录，会留在原地并记为不安全，不删除。

状态目录最好和 kenex 根在同一个文件系统上，这样领取是一次原子改名。如果改名跨设备，模拟器会先复制到 inflight、写下领取记录，再删除原文。这个窗口里进程崩溃时，重启用领取记录补提交，不会因为原文还在就再提交一次。

## 谁拥有哪些文件

| 位置 | 谁写 | 谁读 | 模拟器会不会删 |
|---|---|---|---|
| `outgoing/mt`、`outgoing/mx` | 支付应用 | 模拟器领取 | 只在成功处理后把**这一份**移动到 `temp/outgoing`。失败则移动到 `.swiftsim/error`。移不动就留在原地。不删除还没领取的文件 |
| `incoming/mt`、`incoming/mx` | 模拟器新增文件 | 支付应用 | 不清理、不删除、不覆盖已有文件。写完就结束 |
| `temp/outgoing/mt`、`temp/outgoing/mx` | 模拟器（成功归档） | 排障 / 日志 | 只移入新文件。同名则加后缀。不删除这里已有的内容 |
| `temp/incoming` | 支付应用 | 支付应用 | 完全不访问 |
| `.swiftsim/inflight` | 模拟器 | 模拟器 | 处理完移到 `temp/outgoing` 或 `.swiftsim/error` |
| `.swiftsim/error` | 模拟器 | 排障 | 失败和回声。不删除已有文件。同名加 `-2`、`-3`。旁边有 `.reason.txt` |
| `data/inbox`、`data/outbox` | 模拟器原有文件口 | 模拟器 / 调试 | 与 kenex 无关，行为不变。`data/outbox` 不是 `temp/outgoing` |
| `data/file-transport.properties` | 管理端 | 下次启动 | 模拟器配置，不是报文 |

不支持的类型（包括 MT300、MT320、MT330）不会新增交易处理，也不会被悄悄丢掉。现有解析返回 `SIM-T01`（或其它 `SIM-` 错误）时，原文进入 `.swiftsim/error`，原因写在旁边的 `.reason.txt`，管理端「文件传输」页和日志都能看到。发报行是被测系统时，NAK 仍会作为交给支付应用的报文出现在 `incoming`。

## 崩溃和重复

不是精确一次。

* 轮询不会重复提交已经领取走的文件。
* 重启时，状态为 `CLAIMED` 且库里还没有对应 `fileClaimId` 的，会提交一次；已经有报文的，只把 inflight 归档，不再提交。
* 交到 `incoming` 的文件按报文 id 记账。名字先记入数据库再链接到目录里，所以同一次发布不会因为重启而换一个新文件名再写一份。
* 若支付应用在模拟器把发布记为 `PUBLISHED` 之前就把该文件从 `incoming` 挪走（窗口很短），重启可能把同一报文再写出来。
* 网关在标记已投递之前崩溃时，原有逻辑仍会补投递；文件夹发布跟着那一次补投递，并按上面的记账避免再写一个不同名的副本。

## 和原来的 HTTP 怎么并存

`HTTP` 模式下，即使配置了根目录，轮询也不会打开它。`BOTH` 用来一边保留现有 HTTP 调用，一边接支付应用的目录。管理端、`/api/**` 和 8080 上的页面保持可用。不要在支付应用里再实现一套 SFTP 客户端。
