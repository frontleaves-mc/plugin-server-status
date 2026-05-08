package com.frontleaves.plugins.serverStatus.grpc;

import com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusProto;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * gRPC 状态服务客户端门面，提供事件上报便捷方法。
 * <p>
 * 内部委托 {@link ServerEventStreamHandler} 通过 Client Stream 发送事件，
 * 所有方法均为 fire-and-forget 语义（流断开时静默丢弃）。
 *
 * @author xiao_lfeng
 * @version 2.0.0
 */
public class StatusGrpcService {

    private final ServerEventStreamHandler eventStreamHandler;
    private final String serverName;

    public StatusGrpcService(
            @NotNull ServerEventStreamHandler eventStreamHandler,
            @NotNull String serverName
    ) {
        this.eventStreamHandler = eventStreamHandler;
        this.serverName = serverName;
    }

    /**
     * 玩家加入服务器
     */
    public void playerJoin(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @Nullable String groupName) {
        ServerStatusProto.PlayerJoinEvent.Builder builder = ServerStatusProto.PlayerJoinEvent.newBuilder()
                .setPlayerUuid(uuid)
                .setPlayerName(name)
                .setServerName(serverName)
                .setWorldName(worldName);
        if (groupName != null) {
            builder.setGroupName(groupName);
        }
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_JOIN)
                .setPlayerJoinEvent(builder.build())
                .build());
    }

    /**
     * 玩家离开服务器
     */
    public void playerQuit(@NotNull String uuid, @NotNull String name, @NotNull String worldName) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_QUIT)
                .setPlayerQuitEvent(ServerStatusProto.PlayerQuitEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setPlayerName(name)
                        .setServerName(serverName)
                        .build())
                .build());
    }

    /**
     * 玩家切换世界
     */
    public void playerSwitchWorld(@NotNull String uuid, @NotNull String newWorldName) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_SWITCH_WORLD)
                .setPlayerSwitchWorldEvent(ServerStatusProto.PlayerSwitchWorldEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setNewWorldName(newWorldName)
                        .setServerName(serverName)
                        .build())
                .build());
    }

    /**
     * 服务器心跳上报（仅 TPS，在线人数由 Go 后端通过 Redis 自动计算）
     */
    public void heartbeat(double tps) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_HEARTBEAT)
                .setHeartbeatEvent(ServerStatusProto.HeartbeatEvent.newBuilder()
                        .setServerName(serverName)
                        .setTps(tps)
                        .build())
                .build());
    }

    /**
     * 玩家聊天消息
     */
    public void playerChat(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String message) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_CHAT)
                .setPlayerChatEvent(ServerStatusProto.PlayerChatEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setPlayerName(name)
                        .setServerName(serverName)
                        .setWorldName(worldName)
                        .setMessage(message)
                        .build())
                .build());
    }

    /**
     * 玩家被踢出
     */
    public void playerKick(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String reason) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_KICK)
                .setPlayerKickEvent(ServerStatusProto.PlayerKickEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setPlayerName(name)
                        .setServerName(serverName)
                        .setWorldName(worldName)
                        .setReason(reason)
                        .build())
                .build());
    }

    /**
     * 玩家死亡
     */
    public void playerDeath(@NotNull String uuid, @NotNull String name, @NotNull String worldName, @NotNull String deathMessage) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_DEATH)
                .setPlayerDeathEvent(ServerStatusProto.PlayerDeathEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setPlayerName(name)
                        .setServerName(serverName)
                        .setWorldName(worldName)
                        .setDeathMessage(deathMessage)
                        .build())
                .build());
    }

    /**
     * 玩家权限组变更
     */
    public void playerGroupChange(@NotNull String uuid, @NotNull String name, @NotNull String groupName, @NotNull String oldGroupName) {
        eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_PLAYER_GROUP_CHANGE)
                .setPlayerGroupChangeEvent(ServerStatusProto.PlayerGroupChangeEvent.newBuilder()
                        .setPlayerUuid(uuid)
                        .setPlayerName(name)
                        .setGroupName(groupName)
                        .setOldGroupName(oldGroupName)
                        .build())
                .build());
    }
}
