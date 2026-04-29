package com.frontleaves.plugins.serverStatus.service;

import org.bukkit.Bukkit;

import java.util.LinkedList;

/**
 * 服务器状态采集器，负责 TPS 计算和在线玩家数统计。
 * <p>
 * TPS（Ticks Per Second）通过记录每个 tick 的时间戳，
 * 在指定窗口内计算实际执行频率来得出。
 *
 * @author xiao_lfeng
 * @version 1.0.0
 */
public class StatusCollector {

    private static final int TICKS_INSTANT = 100;
    private static final int MAX_TICK_HISTORY = 13000;

    private final LinkedList<Long> tickTimestamps = new LinkedList<>();

    /**
     * 每个游戏 tick 调用一次，记录时间戳用于计算 TPS。
     */
    public void recordTick() {
        tickTimestamps.add(System.currentTimeMillis());
        if (tickTimestamps.size() > MAX_TICK_HISTORY) {
            tickTimestamps.removeFirst();
        }
    }

    /**
     * 计算瞬时 TPS（基于最近 100 tick 窗口）。
     *
     * @return 当前 TPS 值，上限为 20.0
     */
    public double calculateTps() {
        return calculateTpsForWindow(TICKS_INSTANT);
    }

    /**
     * 计算指定窗口内的 TPS。
     *
     * @param maxTicks 窗口大小（tick 数）
     * @return 窗口内的 TPS 值，上限为 20.0
     */
    public double calculateTpsForWindow(int maxTicks) {
        int size = Math.min(tickTimestamps.size(), maxTicks);
        if (size < 2) {
            return 20.0;
        }
        long first = tickTimestamps.get(tickTimestamps.size() - size);
        long last = tickTimestamps.getLast();
        long elapsed = last - first;
        if (elapsed <= 0) {
            return 20.0;
        }
        return Math.min((size - 1) * 1000.0 / elapsed, 20.0);
    }

    /**
     * 获取当前在线玩家数量。
     *
     * @return 在线玩家数
     */
    public int getOnlinePlayerCount() {
        return Bukkit.getOnlinePlayers().size();
    }
}
