package com.frontleaves.plugins.serverStatus.grpc;

import com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusProto;
import com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * gRPC 状态服务客户端，使用 Unary RPC 模式与 Go 后端通信。
 * 提供 7 个 RPC 调用方法（不含 GetPlayerStatus/GetServerStatus 查询方法），
 * 通过 safeCall 包装统一处理异常。
 *
 * @author xiao_lfeng
 * @version 1.0.0
 */
public class StatusGrpcService {

    private final JavaPlugin plugin;
    private final ServerStatusServiceGrpc.ServerStatusServiceBlockingStub blockingStub;
    private final String serverName;

    public StatusGrpcService(@NotNull JavaPlugin plugin, @NotNull ManagedChannel channel, @NotNull String serverName) {
        this.plugin = plugin;
        this.blockingStub = ServerStatusServiceGrpc.newBlockingStub(channel);
        this.serverName = serverName;
    }

    /**
     * 玩家加入服务器
     */
    public void playerJoin(@NotNull String uuid, @NotNull String name, @NotNull String worldName) {
        safeCall("PlayerJoin", () -> {
            ServerStatusProto.PlayerEventRequest request = ServerStatusProto.PlayerEventRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setPlayerName(name)
                    .setServerName(serverName)
                    .setWorldName(worldName)
                    .build();
            blockingStub.playerJoin(request);
        });
    }

    /**
     * 玩家离开服务器
     */
    public void playerQuit(@NotNull String uuid, @NotNull String name, @NotNull String worldName) {
        safeCall("PlayerQuit", () -> {
            ServerStatusProto.PlayerEventRequest request = ServerStatusProto.PlayerEventRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setPlayerName(name)
                    .setServerName(serverName)
                    .setWorldName(worldName)
                    .build();
            blockingStub.playerQuit(request);
        });
    }

    /**
     * 玩家切换世界
     */
    public void playerSwitchWorld(@NotNull String uuid, @NotNull String newWorldName) {
        safeCall("PlayerSwitchWorld", () -> {
            ServerStatusProto.PlayerSwitchWorldRequest request = ServerStatusProto.PlayerSwitchWorldRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setNewWorldName(newWorldName)
                    .setServerName(serverName)
                    .build();
            blockingStub.playerSwitchWorld(request);
        });
    }

    /**
     * 服务器心跳上报
     */
    public void heartbeat(int onlinePlayers, double tps) {
        safeCall("ServerHeartbeat", () -> {
            ServerStatusProto.ServerHeartbeatRequest request = ServerStatusProto.ServerHeartbeatRequest.newBuilder()
                    .setServerName(serverName)
                    .setOnlinePlayers(onlinePlayers)
                    .setTps(tps)
                    .build();
            blockingStub.serverHeartbeat(request);
        });
    }

    /**
     * 玩家聊天消息
     */
    public void playerChat(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String message) {
        safeCall("PlayerChat", () -> {
            ServerStatusProto.PlayerChatRequest request = ServerStatusProto.PlayerChatRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setPlayerName(name)
                    .setServerName(serverName)
                    .setWorldName(worldName)
                    .setMessage(message)
                    .build();
            blockingStub.playerChat(request);
        });
    }

    /**
     * 玩家被踢出
     */
    public void playerKick(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String reason) {
        safeCall("PlayerKick", () -> {
            ServerStatusProto.PlayerKickRequest request = ServerStatusProto.PlayerKickRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setPlayerName(name)
                    .setServerName(serverName)
                    .setWorldName(worldName)
                    .setReason(reason)
                    .build();
            blockingStub.playerKick(request);
        });
    }

    /**
     * 玩家死亡
     */
    public void playerDeath(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String deathMessage) {
        safeCall("PlayerDeath", () -> {
            ServerStatusProto.PlayerDeathRequest request = ServerStatusProto.PlayerDeathRequest.newBuilder()
                    .setPlayerUuid(uuid)
                    .setPlayerName(name)
                    .setServerName(serverName)
                    .setWorldName(worldName)
                    .setDeathMessage(deathMessage)
                    .build();
            blockingStub.playerDeath(request);
        });
    }

    /**
     * 统一异常包装方法，捕获 gRPC 调用异常并记录日志。
     * UNIMPLEMENTED 状态记录为 info 级别（Go 端待实现），
     * 其他异常记录为 warning 级别。
     *
     * @param rpcName RPC 方法名称
     * @param action  要执行的 RPC 调用
     */
    private void   safeCall(@NotNull String rpcName, @NotNull Runnable action) {
        try {
            action.run();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
                plugin.getLogger().info("RPC " + rpcName + " 未实现（Go 端待开发）: "
                        + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            } else {
                plugin.getLogger().warning("RPC " + rpcName + " 调用失败: "
                        + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            }
        }
    }
}
