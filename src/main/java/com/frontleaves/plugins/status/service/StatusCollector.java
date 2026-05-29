package com.frontleaves.plugins.status.service;

import com.frontleaves.plugins.status.grpc.generated.ServerStatusProto;
import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务器状态采集器，负责 TPS 计算和系统信息采集。
 * <p>
 * TPS（Ticks Per Second）通过记录每个 tick 的时间戳，
 * 在指定窗口内计算实际执行频率来得出。
 * 系统信息包括 CPU、内存、磁盘、JVM、版本和世界数据。
 *
 * @author xiao_lfeng
 * @version 2.0.0
 */
public class StatusCollector {

    private static final int TICKS_INSTANT = 100;
    private static final int MAX_TICK_HISTORY = 13000;
    private static final double MAX_TPS = 20.0;

    private final ConcurrentLinkedDeque<Long> tickTimestamps = new ConcurrentLinkedDeque<>();
    private final AtomicInteger tickCount = new AtomicInteger(0);

    /**
     * 每个游戏 tick 调用一次，记录时间戳用于计算 TPS。
     */
    public void recordTick() {
        tickTimestamps.addLast(System.currentTimeMillis());
        int size = tickCount.incrementAndGet();
        if (size > MAX_TICK_HISTORY) {
            tickTimestamps.pollFirst();
            tickCount.decrementAndGet();
        }
    }

    /**
     * 计算瞬时 TPS（基于最近 100 tick 窗口）。
     *
     * @return 当前 TPS 值，上限为 20.0
     */
    public double calculateTps() {
        return this.calculateTpsForWindow(TICKS_INSTANT);
    }

    /**
     * 计算指定窗口内的 TPS。
     *
     * @param maxTicks 窗口大小（tick 数）
     * @return 窗口内的 TPS 值，上限为 20.0
     */
    public double calculateTpsForWindow(int maxTicks) {
        int size = Math.min(tickCount.get(), maxTicks);
        if (size < 2) {
            return MAX_TPS;
        }
        Long last = tickTimestamps.peekLast();
        if (last == null) {
            return MAX_TPS;
        }
        long first = getFromEnd(size);
        long elapsed = last - first;
        if (elapsed <= 0) {
            return MAX_TPS;
        }
        return Math.min((size - 1) * 1000.0 / elapsed, MAX_TPS);
    }

    private long getFromEnd(int fromEnd) {
        int targetIndex = tickCount.get() - fromEnd;
        Iterator<Long> it = tickTimestamps.iterator();
        int currentIndex = 0;
        while (it.hasNext()) {
            Long value = it.next();
            if (currentIndex == targetIndex) {
                return value;
            }
            currentIndex++;
        }
        return System.currentTimeMillis();
    }

    /**
     * 获取当前在线玩家数量。
     *
     * @return 在线玩家数
     */
    public int getOnlinePlayerCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    /**
     * 采集 CPU 信息。
     *
     * @return CPU 信息 protobuf 消息
     */
    public @NotNull ServerStatusProto.CpuInfo collectCpuInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        return ServerStatusProto.CpuInfo.newBuilder()
                .setCores(Runtime.getRuntime().availableProcessors())
                .setUsagePercent(osBean.getProcessCpuLoad() * 100.0)
                .build();
    }

    /**
     * 采集操作系统物理内存信息。
     *
     * @return 内存信息 protobuf 消息
     */
    public @NotNull ServerStatusProto.MemoryInfo collectMemoryInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        long total = osBean.getTotalMemorySize();
        long free = osBean.getFreeMemorySize();
        return ServerStatusProto.MemoryInfo.newBuilder()
                .setTotalBytes(total)
                .setUsedBytes(total - free)
                .setFreeBytes(free)
                .build();
    }

    /**
     * 采集服务器运行目录的磁盘使用信息。
     *
     * @return 磁盘信息 protobuf 消息
     */
    public @NotNull ServerStatusProto.DiskInfo collectDiskInfo() {
        File root = new File(".");
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        return ServerStatusProto.DiskInfo.newBuilder()
                .setTotalBytes(total)
                .setUsedBytes(total - free)
                .build();
    }

    /**
     * 采集 JVM 堆内存信息。
     *
     * @return JVM 信息 protobuf 消息
     */
    public @NotNull ServerStatusProto.JvmInfo collectJvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        return ServerStatusProto.JvmInfo.newBuilder()
                .setMaxMemoryBytes(runtime.maxMemory())
                .setUsedMemoryBytes(runtime.totalMemory() - runtime.freeMemory())
                .build();
    }

    /**
     * 采集服务器和 Minecraft 版本信息。
     *
     * @return 版本信息 protobuf 消息
     */
    public @NotNull ServerStatusProto.ServerVersionInfo collectVersionInfo() {
        return ServerStatusProto.ServerVersionInfo.newBuilder()
                .setServerVersion(Bukkit.getVersion())
                .setMcVersion(Bukkit.getMinecraftVersion())
                .build();
    }

    /**
     * 采集所有已加载世界的信息。
     *
     * @return 世界信息 protobuf 消息列表
     */
    public @NotNull Iterable<ServerStatusProto.WorldInfo> collectWorldInfos() {
        return Bukkit.getWorlds().stream()
                .map(world -> ServerStatusProto.WorldInfo.newBuilder()
                        .setWorldName(world.getName())
                        .setPlayerCount(world.getPlayers().size())
                        .setEntityCount(world.getEntities().size())
                        .setLoadedChunks(world.getLoadedChunks().length)
                        .build())
                .toList();
    }
}
