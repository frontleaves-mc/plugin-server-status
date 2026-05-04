# AGENTS.md — server-status

> 本文件为 AI 代理提供项目上下文，确保修改代码时遵循已有架构和约定。

## 项目概述

Paper 1.21.1 插件，作为 gRPC 客户端将 Minecraft 服务器实时状态和玩家事件上报至 Go 后端（frontleaves-plugin）。

- **主类**: `ServerStatus.java` — 生命周期管理（onEnable / onDisable）
- **gRPC 模式**: Unary RPC（BlockingStub）+ 双向流式 RPC（AsyncStub）
- **认证**: `plugin-name` + `plugin-secret-key` 通过 gRPC metadata 鉴权，由 frontleaves-lib 提供
- **软依赖**: LuckPerms（未安装时优雅降级）

## 架构

```
                    ┌─────────────────────────────────────────────┐
                    │          Minecraft Server (Paper 1.21.1)    │
                    │                                             │
                    │  EventListener  ──runTaskAsync──┐          │
                    │         │                       │          │
                    │  LuckPermsHook ──runTaskAsync──┤          │
                    │         │                       ▼          │
                    │         │              StatusGrpcService   │
                    │         │              (BlockingStub)      │
                    │         │              + safeCall 包装     │
                    │         │                       │          │
                    │  StatusCollector (TPS)          │          │
                    │         │                       │          │
                    │         ▼                       ▼          │
                    │  ServerQueryStreamHandler ◄── AsyncStub    │
                    │  (双向流 + 自动重连)                         │
                    └─────────────────────┬─────────────────────┘
                                          │ gRPC
                                          ▼
                                  ┌───────────────┐
                                  │  Go Backend   │
                                  │  (Redis+API)  │
                                  └───────────────┘
```

## 目录结构

```
src/main/
├── java/com/frontleaves/plugins/serverStatus/
│   ├── ServerStatus.java                    # 主类：生命周期管理、组件初始化与关闭
│   ├── grpc/
│   │   ├── StatusGrpcService.java           # Unary RPC 客户端 (BlockingStub + AsyncStub)
│   │   └── ServerQueryStreamHandler.java    # 双向流处理器 (AsyncStub + 自动重连)
│   ├── listener/
│   │   └── EventListener.java               # Bukkit 事件监听 (6 个事件处理器)
│   ├── luckperms/
│   │   └── LuckPermsHook.java               # LuckPerms EventBus 集成 + 权限组缓存
│   └── service/
│       └── StatusCollector.java             # TPS 计算器 + 在线玩家数统计
├── proto/
│   ├── link/base.proto                      # BaseResponse 统一响应定义（由 frontleaves-lib 提供）
│   └── status/v1/status.proto               # ServerStatusService 协议定义 (9 RPC)
└── resources/
    ├── config.yml                           # 默认配置（grpc.host/port/server-name + auth.secret-key）
    └── paper-plugin.yml                     # Paper 插件描述（依赖 frontleaves-lib + LuckPerms）
```

## RPC 列表

### Unary RPC (Java → Go，BlockingStub)

| RPC | 请求类型 | Go 端状态 | 说明 |
|-----|---------|-----------|------|
| `PlayerJoin` | `PlayerEventRequest` | 已实现 | 玩家加入（含 groupName 字段） |
| `PlayerQuit` | `PlayerEventRequest` | 已实现 | 玩家离开 |
| `PlayerSwitchWorld` | `PlayerSwitchWorldRequest` | 已实现 | 切换世界 |
| `ServerHeartbeat` | `ServerHeartbeatRequest` | 已实现 | 心跳上报（在线玩家数 + TPS） |
| `PlayerChat` | `PlayerChatRequest` | 待实现 | 聊天消息 |
| `PlayerKick` | `PlayerKickRequest` | 待实现 | 被踢出 |
| `PlayerDeath` | `PlayerDeathRequest` | 待实现 | 死亡 |
| `PlayerGroupChange` | `PlayerGroupChangeRequest` | 待实现 | 权限组变更（LuckPerms 触发） |

### 双向流式 RPC (AsyncStub)

| RPC | 方向 | 说明 |
|-----|------|------|
| `ServerQuery` | Go → Java → Go | Go 端发送 `ServerQueryResponse`（查询请求），Java 端处理并返回 `ServerQueryRequest`（查询结果） |

**ServerQuery 支持的查询事件 (QueryEvent enum)**:

| 事件 | 编号 | 依赖 | 说明 |
|------|------|------|------|
| `QUERY_EVENT_GET_PLAYER_STATUS` | 1 | 无 | 查询玩家在线状态、所在服务器/世界 |
| `QUERY_EVENT_GET_SERVER_STATUS` | 2 | 无 | 查询在线玩家列表、TPS |
| `QUERY_EVENT_CHECK_PERMISSION` | 3 | LuckPerms | 检查玩家权限节点 |
| `QUERY_EVENT_GET_PLAYER_GROUPS` | 4 | LuckPerms | 获取玩家主权限组和所有权限组 |

## 关键设计模式

### safeCall 包装

所有 Unary RPC 调用通过 `StatusGrpcService.safeCall()` 包装：
- `UNIMPLEMENTED` 状态码 → `info` 级别日志（Go 端待开发）
- 其他异常 → `warning` 级别日志
- 绝不抛出异常到调用方

### 异步执行

- 所有事件处理中的 RPC 调用通过 `Bukkit.getScheduler().runTaskAsynchronously()` 执行
- 心跳上报使用 `runTaskTimerAsynchronously()` 定时任务
- TPS 采集使用 `runTaskTimer()` 在主线程执行（每 tick 记录时间戳）

### 双向流自动重连

`ServerQueryStreamHandler` 使用指数退避重连：
- 初始延迟 5000ms，每次翻倍，最大 60000ms
- 连接成功后重置延迟
- `requestObserver` 声明为 `volatile` 保证线程可见性
- 重连由 `ScheduledExecutorService`（守护线程）驱动

### LuckPerms 优雅降级

- `LuckPermsHook.init()` 检测 LuckPerms 是否安装，未安装返回 `null`
- `EventListener` 和 `ServerQueryStreamHandler` 对 `luckPermsHook/luckPerms == null` 做空安全处理
- 权限查询类事件在 LuckPerms 不可用时返回默认值（如 `permissionHas=false`）
- 使用 `EventBus.subscribe(plugin, ...)` 绑定插件生命周期，自动清理

### 线程安全

- `StatusCollector` 使用 `ConcurrentLinkedDeque` + `AtomicInteger`
- `LuckPermsHook.groupCache` 使用 `ConcurrentHashMap`
- `ServerQueryStreamHandler.requestObserver` 使用 `volatile`

### 权限组去重

`LuckPermsHook` 维护 `groupCache`（UUID → 主权限组），`UserDataRecalculateEvent` 触发时对比新旧值，相同则跳过上报。

## 代码风格

- **Java 21**，使用 switch 箭头语法、var 局部变量推断
- **`this.` 使用规则**：调用内部方法或继承方法时使用 `this.`，访问成员变量时不使用
- **空安全注解**：`@NotNull` / `@Nullable`（JetBrains annotations），参数和返回值必须标注
- **Optional 优先**：判空场景优先使用 `Optional.ofNullable().map/.orElse()` 链式调用
- **@Contract**：构造函数等纯方法使用 `@Contract(pure = true)` 标注
- **不使用 @SuppressWarnings**：通过设计（如 volatile、线程安全集合）避免编译警告
- **Javadoc**：所有 public 方法必须有 Javadoc，使用中文说明
- **异常日志**：使用 `Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName())` 避免 null message

## 依赖

| 依赖 | 版本 | 作用域 | 说明 |
|------|------|--------|------|
| Paper API | 1.21.1-R0.1-SNAPSHOT | provided | Bukkit API |
| frontleaves-lib | 1.0.0 | provided | gRPC 通道管理 + 认证拦截器 |
| gRPC (netty-shaded/protobuf/stub/services) | 1.62.2 | provided | gRPC 框架 |
| Protobuf | 3.25.3 | provided | Protocol Buffers |
| LuckPerms API | 5.5 | provided | 权限组管理（软依赖） |
| javax.annotation-api | 1.3.2 | provided | @NotNull/@Nullable 注解 |

## 构建 & 验证

```bash
# 编译（需要 JDK 21+，且 frontleaves-lib 已安装到本地 Maven 仓库）
mvn clean compile

# 打包
mvn clean package
# 产物: target/server-status-1.0.0.jar
```

## 约束 & 注意事项

1. **Proto 字段编号约定**: 业务 message 的字段编号从 11 开始（1-10 预留给内部/公共字段如 `BaseResponse`），新增字段时不可修改已有编号
2. **禁止在主线程执行 gRPC 调用**: 所有 RPC 调用必须通过 `runTaskAsynchronously` 或异步定时任务执行
3. **LuckPerms 是软依赖**: 任何使用 LuckPerms API 的地方必须做空安全检查，不可假设其存在
4. **gRPC 通道由 frontleaves-lib 管理**: 不要自行创建或关闭 `ManagedChannel`（主类 `onDisable` 中调用 `channel.shutdownNow()` 是唯一例外）
5. **EventBus subscribe 绑定插件**: `subscribe(plugin, EventClass, handler)` 自动在插件卸载时取消订阅，无需手动 unregister
6. **TPS 采集必须在主线程**: `recordTick()` 依赖 Bukkit 调度器主线程 tick，不可异步化
7. **config.yml 中 `plugin-secret-key` 为必填项**: 为空时插件在 `onEnable` 阶段拒绝启动（`setEnabled(false)`）
