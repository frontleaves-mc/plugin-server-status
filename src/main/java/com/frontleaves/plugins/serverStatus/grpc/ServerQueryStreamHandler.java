package com.frontleaves.plugins.serverStatus.grpc;

import com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusProto;
import com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusServiceGrpc;
import com.frontleaves.plugins.serverStatus.service.StatusCollector;
import io.grpc.stub.StreamObserver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ServerQuery 双向流处理器，接收 Go 后端的查询请求并返回结果。
 * <p>
 * 使用 oneof 消息实现类型安全的查询/结果对，
 * 支持自动重连（指数退避），通过 volatile + synchronized 保证线程安全。
 *
 * @author xiao_lfeng
 * @version 2.0.0
 */
public class ServerQueryStreamHandler {

    private static final long INITIAL_RETRY_DELAY_MS = 5000;
    private static final long MAX_RETRY_DELAY_MS = 60000;

    private final JavaPlugin plugin;
    private final ServerStatusServiceGrpc.ServerStatusServiceStub asyncStub;
    private final LuckPerms luckPerms;
    private final StatusCollector statusCollector;
    private final String serverName;
    private final ScheduledExecutorService retryExecutor;

    private volatile StreamObserver<ServerStatusProto.ServerQueryRequest> requestObserver;
    private volatile boolean running = false;
    private long retryDelayMs = INITIAL_RETRY_DELAY_MS;

    public ServerQueryStreamHandler(
            @NotNull JavaPlugin plugin,
            @NotNull ServerStatusServiceGrpc.ServerStatusServiceStub asyncStub,
            @Nullable LuckPerms luckPerms,
            @NotNull StatusCollector statusCollector,
            @NotNull String serverName
    ) {
        this.plugin = plugin;
        this.asyncStub = asyncStub;
        this.luckPerms = luckPerms;
        this.statusCollector = statusCollector;
        this.serverName = serverName;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerQuery-Retry");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动双向流连接，失败时自动重连。
     */
    public void startWithRetry() {
        retryExecutor.submit(this::connect);
    }

    /**
     * 建立 ServerQuery 双向流连接。
     */
    public void connect() {
        running = true;
        StreamObserver<ServerStatusProto.ServerQueryResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerStatusProto.ServerQueryResponse value) {
                handleRequest(value);
            }

            @Override
            public void onError(Throwable t) {
                plugin.getLogger().warning("ServerQuery 流错误: "
                        + Optional.ofNullable(t.getMessage()).orElse(t.getClass().getSimpleName()));
                synchronized (ServerQueryStreamHandler.this) {
                    requestObserver = null;
                }
                if (running) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onCompleted() {
                plugin.getLogger().info("ServerQuery 流已关闭");
                synchronized (ServerQueryStreamHandler.this) {
                    requestObserver = null;
                }
                if (running) {
                    scheduleReconnect();
                }
            }
        };
        synchronized (this) {
            requestObserver = asyncStub.serverQuery(responseObserver);
        }
        retryDelayMs = INITIAL_RETRY_DELAY_MS;
        plugin.getLogger().info("ServerQuery 双向流已建立");
    }

    private void scheduleReconnect() {
        plugin.getLogger().info("ServerQuery 将在 " + (retryDelayMs / 1000) + " 秒后重连...");
        retryExecutor.schedule(() -> {
            if (running) {
                connect();
            }
        }, retryDelayMs, TimeUnit.MILLISECONDS);
        retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
    }

    /**
     * 关闭双向流连接和重连调度器。
     */
    public void shutdown() {
        running = false;
        retryExecutor.shutdownNow();
        synchronized (this) {
            if (requestObserver != null) {
                requestObserver.onCompleted();
                requestObserver = null;
            }
        }
    }

    private void handleRequest(@NotNull ServerStatusProto.ServerQueryResponse query) {
        try {
            String requestId = query.getRequestId();
            switch (query.getQueryCase()) {
                case PLAYER_STATUS_QUERY -> this.handleGetPlayerStatus(requestId, query.getPlayerStatusQuery());
                case SERVER_STATUS_QUERY -> this.handleGetServerStatus(requestId, query.getServerStatusQuery());
                case CHECK_PERMISSION_QUERY -> this.handleCheckPermission(requestId, query.getCheckPermissionQuery());
                case PLAYER_GROUPS_QUERY -> this.handleGetPlayerGroups(requestId, query.getPlayerGroupsQuery());
                default -> plugin.getLogger().warning("未知查询类型: " + query.getQueryCase());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("处理查询请求失败: "
                    + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
        }
    }

    private void handleGetPlayerStatus(
            @NotNull String requestId,
            @NotNull ServerStatusProto.QueryPlayerStatusQuery query
    ) {
        UUID uuid = UUID.fromString(query.getPlayerUuid());
        var player = Bukkit.getPlayer(uuid);
        ServerStatusProto.QueryPlayerStatusResult.Builder resultBuilder = ServerStatusProto.QueryPlayerStatusResult.newBuilder();
        if (player != null) {
            resultBuilder.setOnline(true)
                    .setServerName(serverName)
                    .setWorldName(player.getWorld().getName())
                    .setPlayerName(player.getName())
                    .setLastSeen(System.currentTimeMillis());
        } else {
            resultBuilder.setOnline(false);
        }
        this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                .setRequestId(requestId)
                .setPlayerStatusResult(resultBuilder.build())
                .build());
    }

    private void handleGetServerStatus(
            @NotNull String requestId,
            @NotNull ServerStatusProto.QueryServerStatusQuery query
    ) {
        ServerStatusProto.QueryServerStatusResult.Builder resultBuilder = ServerStatusProto.QueryServerStatusResult.newBuilder()
                .setOnlinePlayers(statusCollector.getOnlinePlayerCount())
                .setTps(statusCollector.calculateTps())
                .setLastHeartbeat(System.currentTimeMillis());
        for (var player : Bukkit.getOnlinePlayers()) {
            resultBuilder.addPlayers(ServerStatusProto.PlayerStatus.newBuilder()
                    .setPlayerUuid(player.getUniqueId().toString())
                    .setPlayerName(player.getName())
                    .setWorldName(player.getWorld().getName())
                    .build());
        }
        this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                .setRequestId(requestId)
                .setServerStatusResult(resultBuilder.build())
                .build());
    }

    private void handleCheckPermission(
            @NotNull String requestId,
            @NotNull ServerStatusProto.QueryCheckPermissionQuery query
    ) {
        if (luckPerms == null) {
            this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                    .setRequestId(requestId)
                    .setCheckPermissionResult(ServerStatusProto.QueryCheckPermissionResult.newBuilder()
                            .setHasPermission(false)
                            .build())
                    .build());
            return;
        }
        UUID uuid = UUID.fromString(query.getPlayerUuid());
        String node = query.getPermissionNode();
        User user = luckPerms.getUserManager().getUser(uuid);
        boolean has = user != null && user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
        this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                .setRequestId(requestId)
                .setCheckPermissionResult(ServerStatusProto.QueryCheckPermissionResult.newBuilder()
                        .setHasPermission(has)
                        .build())
                .build());
    }

    private void handleGetPlayerGroups(
            @NotNull String requestId,
            @NotNull ServerStatusProto.QueryPlayerGroupsQuery query
    ) {
        if (luckPerms == null) {
            this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                    .setRequestId(requestId)
                    .setPlayerGroupsResult(ServerStatusProto.QueryPlayerGroupsResult.newBuilder()
                            .build())
                    .build());
            return;
        }
        UUID uuid = UUID.fromString(query.getPlayerUuid());
        User user = luckPerms.getUserManager().getUser(uuid);
        ServerStatusProto.QueryPlayerGroupsResult.Builder resultBuilder = ServerStatusProto.QueryPlayerGroupsResult.newBuilder();
        if (user != null) {
            resultBuilder.setPrimaryGroup(user.getPrimaryGroup());
            user.getNodes().stream()
                    .filter(node -> node instanceof net.luckperms.api.node.types.InheritanceNode)
                    .map(node -> ((net.luckperms.api.node.types.InheritanceNode) node).getGroupName())
                    .forEach(resultBuilder::addGroups);
        }
        this.sendResponse(ServerStatusProto.ServerQueryRequest.newBuilder()
                .setRequestId(requestId)
                .setPlayerGroupsResult(resultBuilder.build())
                .build());
    }

    private void sendResponse(@NotNull ServerStatusProto.ServerQueryRequest request) {
        try {
            StreamObserver<ServerStatusProto.ServerQueryRequest> observer;
            synchronized (this) {
                observer = requestObserver;
            }
            if (observer != null) {
                observer.onNext(request);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("发送查询响应失败: "
                    + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
        }
    }
}
