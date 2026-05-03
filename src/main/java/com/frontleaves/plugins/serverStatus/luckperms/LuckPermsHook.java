package com.frontleaves.plugins.serverStatus.luckperms;

import com.frontleaves.plugins.serverStatus.grpc.StatusGrpcService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LuckPerms 集成钩子，监听权限组变更事件并上报至 Go 后端。
 * <p>
 * 通过 EventBus 订阅实现自动清理（绑定到插件生命周期），
 * 使用 ConcurrentHashMap 做去重避免重复上报。
 *
 * @author xiao_lfeng
 * @version 1.0.0
 */
public class LuckPermsHook {

    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;
    private final StatusGrpcService grpcService;
    private final ConcurrentHashMap<UUID, String> groupCache = new ConcurrentHashMap<>();

    @Contract(pure = true)
    private LuckPermsHook(@NotNull JavaPlugin plugin, @NotNull LuckPerms luckPerms, @NotNull StatusGrpcService grpcService) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.grpcService = grpcService;
    }

    /**
     * 初始化 LuckPerms 集成。若 LuckPerms 未安装则返回 null（优雅降级）。
     *
     * @param plugin      插件实例
     * @param grpcService gRPC 服务实例
     * @return LuckPermsHook 实例，或 null 表示 LuckPerms 不可用
     */
    @Nullable
    public static LuckPermsHook init(@NotNull JavaPlugin plugin, @NotNull StatusGrpcService grpcService) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            LuckPermsHook hook = new LuckPermsHook(plugin, luckPerms, grpcService);
            luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, hook::onUserDataRecalculateEvent);
            plugin.getLogger().info("LuckPerms 集成已启用");
            return hook;
        } catch (Exception e) {
            plugin.getLogger().warning("LuckPerms 集成初始化失败: "
                    + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * 获取玩家主权限组名称。
     *
     * @param uuid 玩家 UUID
     * @return 主权限组名称，获取失败时返回 null
     */
    @Nullable
    public String getPrimaryGroup(@NotNull UUID uuid) {
        try {
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user == null) {
                return null;
            }
            String group = user.getPrimaryGroup();
            groupCache.put(uuid, group);
            return group;
        } catch (Exception e) {
            plugin.getLogger().warning("获取玩家权限组失败: "
                    + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * 获取 LuckPerms API 实例。
     *
     * @return LuckPerms 实例
     */
    @NotNull
    public LuckPerms getLuckPerms() {
        return luckPerms;
    }

    /**
     * 关闭钩子，清空权限组缓存。
     */
    public void shutdown() {
        groupCache.clear();
    }

    private void onUserDataRecalculateEvent(@NotNull UserDataRecalculateEvent event) {
        UUID uuid = event.getUser().getUniqueId();
        String newGroup = event.getUser().getPrimaryGroup();
        String oldGroup = groupCache.get(uuid);
        if (newGroup.equals(oldGroup)) {
            return;
        }
        groupCache.put(uuid, newGroup);
        String playerName = Optional.ofNullable(Bukkit.getPlayer(uuid))
                .map(p -> p.getName())
                .orElse("");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            grpcService.playerGroupChange(
                    uuid.toString(),
                    playerName,
                    newGroup,
                    Optional.ofNullable(oldGroup).orElse("")
            );
        });
    }
}
