package com.frontleaves.plugins.serverStatus.listener;

import com.frontleaves.plugins.serverStatus.grpc.StatusGrpcService;
import com.frontleaves.plugins.serverStatus.luckperms.LuckPermsHook;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 事件监听器，捕获 Bukkit 事件并通过 StatusGrpcService 异步上报至 Go 后端。
 * <p>
 * 所有 RPC 调用通过 {@code runTaskAsynchronously} 执行，避免阻塞主线程。
 *
 * @author xiao_lfeng
 * @version 1.0.0
 */
public class EventListener implements Listener {

    private final JavaPlugin plugin;
    private final StatusGrpcService grpcService;
    private final LuckPermsHook luckPermsHook;

    @Contract(pure = true)
    public EventListener(@NotNull JavaPlugin plugin, @NotNull StatusGrpcService grpcService, @Nullable LuckPermsHook luckPermsHook) {
        this.plugin = plugin;
        this.grpcService = grpcService;
        this.luckPermsHook = luckPermsHook;
    }

    /** 玩家加入服务器 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String worldName = event.getPlayer().getWorld().getName();
        String groupName = luckPermsHook != null
                ? luckPermsHook.getPrimaryGroup(event.getPlayer().getUniqueId())
                : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerJoin(uuid, name, worldName, groupName));
    }

    /** 玩家离开服务器 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String worldName = event.getPlayer().getWorld().getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerQuit(uuid, name, worldName));
    }

    /** 玩家切换世界 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerSwitchWorld(@NotNull PlayerChangedWorldEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String newWorldName = event.getPlayer().getWorld().getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerSwitchWorld(uuid, newWorldName));
    }

    /** 玩家聊天消息 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChat(@NotNull AsyncChatEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String worldName = event.getPlayer().getWorld().getName();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerChat(uuid, name, worldName, message));
    }

    /** 玩家被踢出 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(@NotNull PlayerKickEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        String name = event.getPlayer().getName();
        String worldName = event.getPlayer().getWorld().getName();
        String reason = PlainTextComponentSerializer.plainText().serialize(event.reason());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerKick(uuid, name, worldName, reason));
    }

    /** 玩家死亡 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        String uuid = event.getEntity().getUniqueId().toString();
        String name = event.getEntity().getName();
        String worldName = event.getEntity().getWorld().getName();
        String deathMessage = Optional.ofNullable(event.deathMessage())
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .orElse("");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> grpcService.playerDeath(uuid, name, worldName, deathMessage));
    }
}
