package com.frontleaves.plugins.serverStatus;

import com.frontleaves.plugins.lib.FrontleavesLib;
import com.frontleaves.plugins.lib.message.Message;
import com.frontleaves.plugins.serverStatus.grpc.ServerEventStreamHandler;
import com.frontleaves.plugins.serverStatus.grpc.ServerQueryStreamHandler;
import com.frontleaves.plugins.serverStatus.grpc.StatusGrpcService;
import com.frontleaves.plugins.serverStatus.listener.EventListener;
import com.frontleaves.plugins.serverStatus.luckperms.LuckPermsHook;
import com.frontleaves.plugins.serverStatus.service.StatusCollector;
import io.grpc.ManagedChannel;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;

/**
 * 锋楪服务器监控插件主类，作为 gRPC 客户端对接 Go 后端，
 * 通过 Client Stream 上报服务器状态和玩家事件。
 *
 * @author xiao_lfeng
 * @version 2.0.0
 */
public final class ServerStatus extends JavaPlugin {

    private ManagedChannel channel;
    private ServerEventStreamHandler eventStreamHandler;
    private StatusGrpcService grpcService;
    private LuckPermsHook luckPermsHook;
    private ServerQueryStreamHandler queryStreamHandler;
    private StatusCollector statusCollector;
    private BukkitTask heartbeatTask;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        String host = this.getConfig().getString("grpc.host", "localhost");
        int port = this.getConfig().getInt("grpc.port", 50051);
        String serverName = this.getConfig().getString("grpc.server-name", "survival");
        int heartbeatInterval = this.getConfig().getInt("grpc.heartbeat-interval-seconds", 5);
        String secretKey = this.getConfig().getString("auth.plugin-secret-key", "");

        if (secretKey.isBlank()) {
            Message.of(this, "监控").console().severe("配置错误：auth.plugin-secret-key 不能为空，请先在配置文件中设置有效的密钥");
            this.setEnabled(false);
            return;
        }

        channel = FrontleavesLib.getInstance()
                .orElseThrow(() -> new IllegalStateException("FrontleavesLib 未加载，请确保插件依赖配置正确"))
                .createChannel(host, port, "server-status", secretKey);
        Message.of(this, "监控").console().sendMessage("<gray>已连接到 Go 后端：<white>" + host + ":" + port);

        var asyncStub = com.frontleaves.plugins.serverStatus.grpc.generated.ServerStatusServiceGrpc.newStub(channel);
        eventStreamHandler = new ServerEventStreamHandler(this, asyncStub);
        grpcService = new StatusGrpcService(eventStreamHandler, serverName);
        eventStreamHandler.startWithRetry();

        luckPermsHook = LuckPermsHook.init(this, grpcService);

        statusCollector = new StatusCollector();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, statusCollector::recordTick, 1L, 1L);

        LuckPerms luckPermsApi = luckPermsHook != null ? luckPermsHook.getLuckPerms() : null;
        queryStreamHandler = new ServerQueryStreamHandler(this, asyncStub, luckPermsApi, statusCollector, serverName);
        queryStreamHandler.startWithRetry();

        Bukkit.getPluginManager().registerEvents(new EventListener(this, grpcService, luckPermsHook), this);
        Message.of(this, "监控").console().info("事件监听器已注册");

        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                grpcService.heartbeat(statusCollector.calculateTps());
            } catch (Exception e) {
                Message.of(this, "监控").console().warning("心跳上报失败：<white>" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            }
        }, heartbeatInterval * 20L, heartbeatInterval * 20L);

        Message.of(this, "监控").console().sendMessage("<gray>心跳定时器已启动，间隔：<white>" + heartbeatInterval + "秒");
        Message.of(this, "监控").console().info("锋楪服务器监控初始化已完成");
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            Message.of(this, "监控").console().info("心跳定时器已取消");
        }

        if (tickTask != null) {
            tickTask.cancel();
            Message.of(this, "监控").console().info("Tick 计时任务已取消");
        }

        if (luckPermsHook != null) {
            luckPermsHook.shutdown();
        }

        if (queryStreamHandler != null) {
            queryStreamHandler.shutdown();
        }

        if (eventStreamHandler != null) {
            eventStreamHandler.shutdown();
        }

        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
            Message.of(this, "监控").console().info("gRPC 通道已关闭");
        }

        Message.of(this, "监控").console().warning("锋楪服务器监控已停止");
    }
}
