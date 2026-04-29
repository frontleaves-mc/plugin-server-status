# gRPC 协议说明 — ServerStatusService

> 文档版本：2026-04-29  
> 协议包名：`frontleaves.status.v1`

---

## 1. 概述

`ServerStatusService` 是一套基于 gRPC Unary RPC 模式的通信协议，用于 Java Minecraft 插件（客户端）与 Go 后端服务（服务端）之间的实时数据交互。

协议职责包括：

- 玩家生命周期事件上报（加入、离开、切换世界、死亡等）
- 服务器心跳与健康监控
- 按玩家或服务器维度查询当前状态

所有 RPC 均为同步 Unary 调用，即客户端发送请求后阻塞等待服务端响应，适用于事件驱动型通信场景。

---

## 2. 认证机制

每次 gRPC 调用均需在 metadata（HTTP/2 HEADERS）中携带以下两个字段进行身份校验：

| Metadata 字段 | 说明 |
|---|---|
| `plugin-name` | 插件注册名称，用于标识调用方身份 |
| `plugin-secret-key` | 插件密钥，用于验证调用方合法性 |

Go 后端通过拦截器（Interceptor）在校验失败时返回 `UNAUTHENTICATED` 状态码，请求不会被路由到业务逻辑层。

---

## 3. RPC 列表

`ServerStatusService` 共定义 9 个 RPC 方法，按职责分为三类。

### 3.1 玩家事件上报

| 方法名 | 用途 | 请求类型 | 响应类型 | Java 端 | Go 端 |
|---|---|---|---|---|---|
| `PlayerJoin` | 玩家加入服务器 | `PlayerEventRequest` | `PlayerEventResponse` | ✅ 已实现 | ✅ 已实现 |
| `PlayerQuit` | 玩家离开服务器 | `PlayerEventRequest` | `PlayerEventResponse` | ✅ 已实现 | ✅ 已实现 |
| `PlayerSwitchWorld` | 玩家切换世界 | `PlayerSwitchWorldRequest` | `PlayerEventResponse` | ✅ 已实现 | ✅ 已实现 |
| `PlayerDeath` | 玩家死亡 | `PlayerDeathRequest` | `PlayerEventResponse` | ✅ 已实现 | ❌ 待实现 |
| `PlayerChat` | 玩家发送聊天消息 | `PlayerChatRequest` | `PlayerEventResponse` | ✅ 已实现 | ❌ 待实现 |
| `PlayerKick` | 玩家被踢出服务器 | `PlayerKickRequest` | `PlayerEventResponse` | ✅ 已实现 | ❌ 待实现 |

### 3.2 服务器心跳

| 方法名 | 用途 | 请求类型 | 响应类型 | Java 端 | Go 端 |
|---|---|---|---|---|---|
| `ServerHeartbeat` | 定期上报服务器状态 | `ServerHeartbeatRequest` | `ServerHeartbeatResponse` | ✅ 已实现 | ✅ 已实现 |

### 3.3 状态查询

| 方法名 | 用途 | 请求类型 | 响应类型 | Java 端 | Go 端 |
|---|---|---|---|---|---|
| `GetPlayerStatus` | 查询玩家在线状态 | `GetPlayerStatusRequest` | `GetPlayerStatusResponse` | 未调用 | ✅ 已实现 |
| `GetServerStatus` | 查询服务器实时状态 | `GetServerStatusRequest` | `GetServerStatusResponse` | 未调用 | ✅ 已实现 |

---

## 4. 消息类型

### 4.1 PlayerEventRequest

用于 `PlayerJoin`、`PlayerQuit`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 玩家 UUID |
| `player_name` | `string` | 12 | 玩家用户名 |
| `server_name` | `string` | 13 | 服务器名称 |
| `world_name` | `string` | 14 | 世界名称 |

### 4.2 PlayerSwitchWorldRequest

用于 `PlayerSwitchWorld`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 玩家 UUID |
| `new_world_name` | `string` | 12 | 切换后的新世界名称 |
| `server_name` | `string` | 13 | 服务器名称 |

### 4.3 ServerHeartbeatRequest

用于 `ServerHeartbeat`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `server_name` | `string` | 11 | 服务器名称 |
| `online_players` | `int32` | 12 | 当前在线玩家数 |
| `tps` | `double` | 13 | 服务器 TPS（Ticks Per Second） |

### 4.4 GetPlayerStatusRequest

用于 `GetPlayerStatus`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 要查询的玩家 UUID |

### 4.5 GetPlayerStatusResponse

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `base_response` | `BaseResponse` | 1 | 统一响应元信息 |
| `online` | `bool` | 11 | 是否在线 |
| `server_name` | `string` | 12 | 所在服务器名称 |
| `world_name` | `string` | 13 | 所在世界名称 |
| `player_name` | `string` | 14 | 玩家用户名 |
| `last_seen` | `int64` | 15 | 最后在线时间（Unix 毫秒） |

### 4.6 GetServerStatusRequest

用于 `GetServerStatus`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `server_name` | `string` | 11 | 要查询的服务器名称 |

### 4.7 GetServerStatusResponse

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `base_response` | `BaseResponse` | 1 | 统一响应元信息 |
| `players` | `repeated PlayerStatus` | 11 | 在线玩家列表 |
| `online_players` | `int32` | 12 | 在线玩家数 |
| `tps` | `double` | 13 | 服务器 TPS |
| `last_heartbeat` | `int64` | 14 | 最后心跳时间（Unix 毫秒） |

### 4.8 PlayerStatus

嵌入于 `GetServerStatusResponse.players` 中。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 1 | 玩家 UUID |
| `player_name` | `string` | 2 | 玩家用户名 |
| `world_name` | `string` | 3 | 所在世界名称 |

### 4.9 PlayerChatRequest

用于 `PlayerChat`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 玩家 UUID |
| `player_name` | `string` | 12 | 玩家用户名 |
| `server_name` | `string` | 13 | 服务器名称 |
| `world_name` | `string` | 14 | 世界名称 |
| `message` | `string` | 15 | 聊天消息内容 |

### 4.10 PlayerKickRequest

用于 `PlayerKick`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 玩家 UUID |
| `player_name` | `string` | 12 | 玩家用户名 |
| `server_name` | `string` | 13 | 服务器名称 |
| `world_name` | `string` | 14 | 世界名称 |
| `reason` | `string` | 15 | 踢出原因 |

### 4.11 PlayerDeathRequest

用于 `PlayerDeath`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `player_uuid` | `string` | 11 | 玩家 UUID |
| `player_name` | `string` | 12 | 玩家用户名 |
| `server_name` | `string` | 13 | 服务器名称 |
| `world_name` | `string` | 14 | 世界名称 |
| `death_message` | `string` | 15 | 死亡消息 |

### 4.12 PlayerEventResponse

用于 `PlayerJoin`、`PlayerQuit`、`PlayerSwitchWorld`、`PlayerChat`、`PlayerKick`、`PlayerDeath`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `base_response` | `BaseResponse` | 1 | 统一响应元信息 |

### 4.13 ServerHeartbeatResponse

用于 `ServerHeartbeat`。

| 字段名 | 类型 | 编号 | 说明 |
|---|---|---|---|
| `base_response` | `BaseResponse` | 1 | 统一响应元信息 |

---

## 5. 统一响应格式

所有 RPC 响应均内嵌 `BaseResponse`（包名 `xBase`），提供统一的元信息结构。

| 字段名 | 类型 | 编号 | 必填 | 说明 |
|---|---|---|---|---|
| `context` | `string` | 1 | 是 | 请求追踪标识，用于日志关联与链路追踪 |
| `output` | `string` | 2 | 是 | 输出标识，如 `"Success"`、`"PARAMETER_ERROR"` |
| `code` | `uint64` | 3 | 是 | 业务状态码，`200` 表示成功，其余对应错误码体系 |
| `message` | `string` | 4 | 是 | 人类可读的描述信息 |
| `error_message` | `optional string` | 5 | 否 | 补充性错误详情，仅在错误场景下填充 |
| `overhead` | `optional int64` | 6 | 否 | 请求处理耗时（微秒），仅在调试模式下填充 |

---

## 6. 典型工作流程

以下描述一台 Minecraft 服务器从启动到关闭的完整 gRPC 交互时序。

### 6.1 服务器启动

插件加载后，开始以固定间隔（如每 30 秒）调用 `ServerHeartbeat`，上报当前在线玩家数和 TPS。首次心跳用于在 Go 后端注册该服务器实例。

### 6.2 玩家加入

玩家通过 BungeeCord/Velocity 代理进入某台服务器时，插件监听 `PlayerJoinEvent` 并调用 `PlayerJoin`，将玩家 UUID、用户名、目标服务器和世界名称发送至 Go 后端。

### 6.3 玩家切换世界

玩家在同一服务器内切换世界（如从主城进入资源世界）时，插件调用 `PlayerSwitchWorld`，更新玩家当前所在世界。

### 6.4 玩家聊天

玩家在游戏内发送聊天消息时，插件调用 `PlayerChat`，将消息内容连同玩家信息一起上报。

### 6.5 玩家死亡

玩家在游戏中死亡时，插件调用 `PlayerDeath`，上报死亡消息（如「Steve 被僵尸杀死了」）。

### 6.6 玩家被踢

管理员或插件踢出玩家时，调用 `PlayerKick`，附带踢出原因。

### 6.7 玩家离开

玩家主动断开连接或被踢后，插件监听 `PlayerQuitEvent` 并调用 `PlayerQuit`，标记玩家离线。

### 6.8 状态查询

Go 后端或其他服务可随时调用 `GetPlayerStatus` 查询指定玩家的在线状态、所在服务器与世界；调用 `GetServerStatus` 查询指定服务器的在线玩家列表、TPS 和最后心跳时间。

### 6.9 服务器关闭

服务器关闭时插件停止发送心跳。Go 后端在心跳超时后自动将该服务器标记为离线。

---

## 7. Go 端待办事项

以下 3 个 RPC 已在 Java 端实现并在 proto 定义中声明，但 Go 后端尚未实现处理逻辑。需在 Go 端补充对应的 Handler 实现。

### 7.1 PlayerChat

- **请求**：`PlayerChatRequest`
- **用途**：接收玩家聊天消息，可用于跨服聊天同步、消息审计等场景
- **建议**：持久化消息记录，或转发至消息队列供下游消费

### 7.2 PlayerKick

- **请求**：`PlayerKickRequest`
- **用途**：记录玩家被踢出事件及原因，用于运维审计与行为分析
- **建议**：记录踢出原因到日志或数据库，关联管理操作记录

### 7.3 PlayerDeath

- **请求**：`PlayerDeathRequest`
- **用途**：上报玩家死亡事件及死亡消息，可用于统计玩家死亡率或生成游戏内动态
- **建议**：持久化死亡记录，或推送至 WebSocket 供前端面板展示
