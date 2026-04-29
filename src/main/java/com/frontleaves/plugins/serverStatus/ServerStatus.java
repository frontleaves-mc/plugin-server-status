package com.frontleaves.plugins.serverStatus;

import com.frontleaves.plugins.lib.FrontleavesLib;
import com.frontleaves.plugins.serverStatus.grpc.StatusGrpcService;
import com.frontleaves.plugins.serverStatus.listener.EventListener;
import com.frontleaves.plugins.serverStatus.service.StatusCollector;
import io.grpc.ManagedChannel;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;

/**
 * 锋楪服务器监控插件主类，作为 gRPC 客户端对接 Go 后端，
 * 通过 Unary RPC 上报服务器状态和玩家事件。
 *
 * @author xiao_lfeng
 * @version 1.0.0
 */
public final class ServerStatus extends JavaPlugin {

    private static final String PREFIX = "<gray>[<green>锋楪<gray>] <reset>";
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private ManagedChannel channel;
    private StatusGrpcService grpcService;
    private BukkitTask heartbeatTask;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 读取配置
        String host = getConfig().getString("grpc.host", "localhost");
        int port = getConfig().getInt("grpc.port", 50051);
        String serverName = getConfig().getString("grpc.server-name", "survival");
        int heartbeatInterval = getConfig().getInt("grpc.heartbeat-interval-seconds", 5);
        String secretKey = getConfig().getString("auth.plugin-secret-key", "");

        // 验证 plugin-secret-key 非空
        if (secretKey.isBlank()) {
            sendConsole("<red>配置错误：auth.plugin-secret-key 不能为空，请先在配置文件中设置有效的密钥");
            setEnabled(false);
            return;
        }

        // 通过 lib 创建到 Go 后端的 gRPC 通道
        channel = FrontleavesLib.getInstance()
                .orElseThrow(() -> new IllegalStateException("FrontleavesLib 未加载，请确保插件依赖配置正确"))
                .createChannel(host, port, "server-status", secretKey);
        sendConsole("<gray>已连接到 Go 后端：<white>" + host + ":" + port);

        // 创建 gRPC 服务
        grpcService = new StatusGrpcService(this, channel, serverName);

        // 注册 tick 计时任务
        StatusCollector collector = new StatusCollector();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, collector::recordTick, 1L, 1L);

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(new EventListener(this, grpcService), this);
        sendConsole("<gray>事件监听器已注册");

        // 启动心跳定时器
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                grpcService.heartbeat(collector.getOnlinePlayerCount(), collector.calculateTps());
            } catch (Exception e) {
                sendConsole("<red>心跳上报失败：<white>" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            }
        }, heartbeatInterval * 20L, heartbeatInterval * 20L);

        sendConsole("<gray>心跳定时器已启动，间隔：<white>" + heartbeatInterval + "秒");
        sendConsole("<green>锋楪服务器监控初始化已完成");
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            sendConsole("<gray>心跳定时器已取消");
        }

        if (tickTask != null) {
            tickTask.cancel();
            sendConsole("<gray>Tick 计时任务已取消");
        }

        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
            sendConsole("<gray>gRPC 通道已关闭");
        }

        sendConsole("<red>锋楪服务器监控已停止");
    }

    private void sendConsole(String message) {
        Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(PREFIX + message));
    }
}
