package com.frontleaves.plugins.status;

import com.frontleaves.plugins.lib.FrontleavesLib;
import com.frontleaves.plugins.lib.grpc.ChannelReloadListener;
import com.frontleaves.plugins.lib.grpc.ConnectivityMonitor;
import com.frontleaves.plugins.lib.message.Message;
import com.frontleaves.plugins.status.grpc.ServerEventStreamHandler;
import com.frontleaves.plugins.status.grpc.generated.ServerStatusProto;
import com.frontleaves.plugins.status.grpc.generated.ServerStatusServiceGrpc;
import com.frontleaves.plugins.status.service.StatusCollector;
import io.grpc.ManagedChannel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;

/**
 * 锋楪服务器监控插件主类，作为 gRPC 客户端对接 Go 后端，
 * 通过 Client Stream 上报服务器心跳和系统状态。
 *
 * @author xiao_lfeng
 * @version 3.0.0
 */
public final class FrontleavesStatus extends JavaPlugin {

    private ManagedChannel channel;
    private ServerEventStreamHandler eventStreamHandler;
    private StatusCollector statusCollector;
    private BukkitTask heartbeatTask;
    private BukkitTask tickTask;
    private BukkitTask snapshotTask;
    private volatile boolean channelReady = false;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        String serverName = this.getConfig().getString("grpc.server-name", "survival");
        int heartbeatInterval = this.getConfig().getInt("grpc.heartbeat-interval-seconds", 5);

        FrontleavesLib lib = FrontleavesLib.getInstance()
                .orElseThrow(() -> new IllegalStateException("FrontleavesLib 未加载，请确保插件依赖配置正确"));

        channel = lib.createChannel("frontleaves-status");
        Message.of(this, "监控").console().sendMessage("<gray>已连接到 Go 后端");

        var asyncStub = ServerStatusServiceGrpc.newStub(channel);
        eventStreamHandler = new ServerEventStreamHandler(this, asyncStub);
        eventStreamHandler.startWithRetry();

        statusCollector = new StatusCollector();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, statusCollector::recordTick, 1L, 1L);

        snapshotTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            statusCollector.refreshSnapshot();
        }, heartbeatInterval * 20L, heartbeatInterval * 20L);

        // 监控通道连接状态
        ConnectivityMonitor monitor = lib.createConnectivityMonitor(this, channel, "frontleaves-status");
        monitor.onReady(() -> {
            channelReady = true;
            Message.of(this, "监控").console().info("gRPC 连接已恢复，心跳将继续上报");
        }).onFailure(() -> {
            channelReady = false;
            Message.of(this, "监控").console().warning("gRPC 连接异常，心跳暂停");
        }).startMonitoring();

        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!channelReady) {
                return;
            }
            try {
                ServerStatusProto.HeartbeatEvent.Builder heartbeatBuilder = ServerStatusProto.HeartbeatEvent.newBuilder()
                        .setServerName(serverName)
                        .setTps(statusCollector.calculateTps())
                        .setOnlinePlayer(statusCollector.getOnlinePlayerCount())
                        .setCpuInfo(statusCollector.collectCpuInfo())
                        .setMemoryInfo(statusCollector.collectMemoryInfo())
                        .setDiskInfo(statusCollector.collectDiskInfo())
                        .setJvmInfo(statusCollector.collectJvmInfo())
                        .setVersionInfo(statusCollector.collectVersionInfo())
                        .addAllWorlds(statusCollector.collectWorldInfos());

                eventStreamHandler.sendEvent(ServerStatusProto.ServerEventStreamRequest.newBuilder()
                        .setEventType(ServerStatusProto.ServerEventType.SERVER_EVENT_TYPE_HEARTBEAT)
                        .setHeartbeatEvent(heartbeatBuilder.build())
                        .build());
            } catch (Exception e) {
                Message.of(this, "监控").console().warning("心跳上报失败：<white>" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            }
        }, heartbeatInterval * 20L, heartbeatInterval * 20L);

        // 注册通道重载回调
        lib.registerPlugin("frontleaves-status", newChannel -> {
            channelReady = false;
            channel = newChannel;
            var newAsyncStub = ServerStatusServiceGrpc.newStub(newChannel);

            // 关闭旧流处理器
            if (eventStreamHandler != null) {
                eventStreamHandler.shutdown();
            }

            // 重建事件流
            eventStreamHandler = new ServerEventStreamHandler(this, newAsyncStub);
            eventStreamHandler.startWithRetry();

            Message.of(this, "监控").console().info("gRPC 通道已重建，流处理器已重启");
        });

        Message.of(this, "监控").console().sendMessage("<gray>心跳定时器已启动，间隔：<white>" + heartbeatInterval + "秒");
        Message.of(this, "监控").console().info("锋楪服务器监控初始化已完成");
    }

    @Override
    public void onDisable() {
        FrontleavesLib.getInstance().ifPresent(lib -> lib.unregisterPlugin("frontleaves-status"));

        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            Message.of(this, "监控").console().info("心跳定时器已取消");
        }

        if (tickTask != null) {
            tickTask.cancel();
            Message.of(this, "监控").console().info("Tick 计时任务已取消");
        }

        if (snapshotTask != null) {
            snapshotTask.cancel();
            Message.of(this, "监控").console().info("快照定时器已取消");
        }

        channelReady = false;

        if (eventStreamHandler != null) {
            eventStreamHandler.shutdown();
            eventStreamHandler = null;
        }

        // 通道由 frontleaves-lib 统一管理，不在此处关闭

        Message.of(this, "监控").console().warning("锋楪服务器监控已停止");
    }
}
