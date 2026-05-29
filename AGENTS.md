# AGENTS.md — frontleaves-status

> 本文件为 AI 代理提供项目上下文，确保修改代码时遵循已有架构和约定。

## 项目概述

Paper 1.21.1 插件，作为 gRPC 客户端将 Minecraft 服务器心跳和系统状态上报至 Go 后端（frontleaves-plugin）。

- **主类**: `FrontleavesStatus.java` — 生命周期管理（onEnable / onDisable）
- **gRPC 模式**: Client Stream（AsyncStub）— 持续上报心跳事件
- **认证**: `plugin-name` + `plugin-secret-key` 通过 gRPC metadata 鉴权，由 frontleaves-lib 提供

## 架构

```
                    ┌─────────────────────────────────────────────┐
                    │          Minecraft Server (Paper 1.21.1)    │
                    │                                             │
                    │  StatusCollector (TPS + 系统信息)            │
                    │         │                                   │
                    │         ▼                                   │
                    │  ServerEventStreamHandler                   │
                    │  (Client Stream + 自动重连)                  │
                    └─────────────────────┬───────────────────────┘
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
├── java/com/frontleaves/plugins/status/
│   ├── FrontleavesStatus.java               # 主类：生命周期管理、组件初始化与关闭
│   ├── grpc/
│   │   └── ServerEventStreamHandler.java    # Client Stream 处理器 (AsyncStub + 自动重连)
│   └── service/
│       └── StatusCollector.java             # TPS 计算器 + 系统信息采集（CPU/内存/磁盘/JVM/版本/世界）
├── proto/
│   ├── link/base.proto                      # BaseResponse 统一响应定义（由 frontleaves-lib 提供）
│   └── status/v1/status.proto               # ServerStatusService 协议定义 (仅心跳)
└── resources/
    ├── config.yml                           # 默认配置（grpc.server-name + heartbeat-interval-seconds，连接参数由 frontleaves-lib 集中管理）
    └── paper-plugin.yml                     # Paper 插件描述（依赖 frontleaves-lib）
```

## 心跳事件字段

HeartbeatEvent 包含以下信息：

| 字段 | 类型 | 说明 |
|------|------|------|
| `server_name` | `string` | 服务器名称 |
| `tps` | `double` | 服务器 TPS |
| `online_player` | `int32` | 在线玩家数量 |
| `cpu_info` | `CpuInfo` | CPU 核心数 + 使用率 |
| `memory_info` | `MemoryInfo` | 操作系统总/已用/空闲内存 |
| `disk_info` | `DiskInfo` | 磁盘总/已用空间 |
| `jvm_info` | `JvmInfo` | JVM 最大/已用堆内存 |
| `version_info` | `ServerVersionInfo` | 服务器版本 + MC 版本 |
| `worlds` | `repeated WorldInfo` | 世界列表（玩家数/实体数/区块数） |

## 关键设计模式

### 系统信息采集

`StatusCollector` 在每次心跳时实时采集：
- **CPU**: `OperatingSystemMXBean.getProcessCpuLoad()` 获取进程 CPU 使用率
- **内存**: `OperatingSystemMXBean.getTotalMemorySize()` / `getFreeMemorySize()` 获取物理内存
- **磁盘**: `File.getTotalSpace()` / `getUsableSpace()` 获取磁盘使用
- **JVM**: `Runtime.maxMemory()` / `totalMemory()` / `freeMemory()` 获取堆内存
- **版本**: `Bukkit.getVersion()` + `Bukkit.getMinecraftVersion()`
- **世界**: `Bukkit.getWorlds()` 遍历获取玩家数/实体数/已加载区块数

### Client Stream 自动重连

`ServerEventStreamHandler` 使用指数退避重连：
- 初始延迟 5000ms，每次翻倍，最大 60000ms
- 连接成功后重置延迟
- `requestObserver` 声明为 `volatile` 保证线程可见性
- 重连由 `ScheduledExecutorService`（守护线程）驱动
- 使用 `generation` 计数器防止旧流回调误杀新流

### ConnectivityMonitor 集成

通过 `frontleaves-lib` 提供的 `ConnectivityMonitor` 监控通道状态：
- `TRANSIENT_FAILURE` 时设置 `channelReady = false`，心跳暂停上报
- `READY` 时设置 `channelReady = true`，心跳恢复

### 线程安全

- `StatusCollector` 使用 `ConcurrentLinkedDeque` + `AtomicInteger`
- `ServerEventStreamHandler.requestObserver` 使用 `volatile`
- `channelReady` 使用 `volatile`

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
| frontleaves-lib | 1.0.0 | provided | gRPC 通道管理 + 认证拦截器 + 消息 API |
| gRPC (netty-shaded/protobuf/stub/services) | 1.62.2 | provided | gRPC 框架 |
| Protobuf | 3.25.3 | provided | Protocol Buffers |
| javax.annotation-api | 1.3.2 | provided | @NotNull/@Nullable 注解 |

## 构建 & 验证

```bash
# 编译（需要 JDK 21+，且 frontleaves-lib 已安装到本地 Maven 仓库）
mvn clean compile

# 打包
mvn clean package
# 产物: target/frontleaves-status-1.0.0.jar
```

## 约束 & 注意事项

1. **Proto 字段编号约定**: 业务 message 的字段编号从 11 开始（1-10 预留给内部/公共字段如 `BaseResponse`），新增字段时不可修改已有编号
2. **禁止在主线程执行 gRPC 调用**: 心跳上报通过 `runTaskTimerAsynchronously()` 执行
3. **gRPC 通道由 frontleaves-lib 管理**: 连接参数（host、port、secretKey）从 lib 的 config.yml 集中读取，业务插件仅调用 `createChannel(pluginName)`。不要自行创建或关闭 `ManagedChannel`。使用 `/frontleaves-lib reload` 可重载配置并重建所有通道
4. **TPS 采集必须在主线程**: `recordTick()` 依赖 Bukkit 调度器主线程 tick，不可异步化
5. **plugin-secret-key 由 frontleaves-lib 统一校验**: secretKey 配置已集中到 lib 的 config.yml，为空时 `createChannel()` 抛出 `IllegalStateException`，业务插件无需自行校验
6. **玩家事件和查询不属于本插件**: PlayerJoin/Quit/Chat/Kick/Death/GroupChange 事件和 ServerQuery 双向流由 essentials 插件管理
