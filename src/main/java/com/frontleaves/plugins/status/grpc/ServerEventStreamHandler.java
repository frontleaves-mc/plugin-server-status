package com.frontleaves.plugins.status.grpc;

import com.frontleaves.plugins.status.grpc.generated.ServerStatusProto;
import com.frontleaves.plugins.status.grpc.generated.ServerStatusServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerEventStream Client Stream 处理器，负责建立持续的事件上报流。
 * <p>
 * 通过一次 Stream 连接持续发送各类事件（心跳、玩家加入/离开等），
 * 支持自动重连（指数退避），使用 synchronized 保证线程安全。
 *
 * @author xiao_lfeng
 * @version 2.0.0
 */
public class ServerEventStreamHandler {

    private static final long INITIAL_RETRY_DELAY_MS = 5000;
    private static final long MAX_RETRY_DELAY_MS = 60000;

    private final JavaPlugin plugin;
    private final ServerStatusServiceGrpc.ServerStatusServiceStub asyncStub;
    private final ScheduledExecutorService retryExecutor;

    private volatile StreamObserver<ServerStatusProto.ServerEventStreamRequest> requestObserver;
    private volatile boolean running = false;
    private volatile long generation = 0;
    private final AtomicLong retryDelayMs = new AtomicLong(INITIAL_RETRY_DELAY_MS);

    public ServerEventStreamHandler(
            @NotNull JavaPlugin plugin,
            @NotNull ServerStatusServiceGrpc.ServerStatusServiceStub asyncStub
    ) {
        this.plugin = plugin;
        this.asyncStub = asyncStub;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerEventStream-Retry");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动事件流连接，失败时自动重连。
     */
    public void startWithRetry() {
        retryExecutor.submit(this::connect);
    }

    /**
     * 建立 ServerEventStream Client Stream 连接。
     */
    public void connect() {
        running = true;
        final long currentGeneration = ++generation;
        StreamObserver<ServerStatusProto.ServerEventStreamResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ServerStatusProto.ServerEventStreamResponse value) {
                // 服务端确认响应，通常忽略
            }

            @Override
            public void onError(Throwable t) {
                // 忽略旧流的延迟回调，避免误杀新流
                if (currentGeneration != generation) {
                    return;
                }
                plugin.getLogger().warning("ServerEventStream 流错误: "
                        + Optional.ofNullable(t.getMessage()).orElse(t.getClass().getSimpleName()));
                synchronized (ServerEventStreamHandler.this) {
                    requestObserver = null;
                }
                if (running) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onCompleted() {
                // 忽略旧流的延迟回调，避免误杀新流
                if (currentGeneration != generation) {
                    return;
                }
                plugin.getLogger().info("ServerEventStream 流已关闭");
                synchronized (ServerEventStreamHandler.this) {
                    requestObserver = null;
                }
                if (running) {
                    scheduleReconnect();
                }
            }
        };
        synchronized (this) {
            requestObserver = asyncStub.serverEventStream(responseObserver);
        }
        retryDelayMs.set(INITIAL_RETRY_DELAY_MS);
        plugin.getLogger().info("ServerEventStream Client Stream 已建立 [generation=" + currentGeneration + "]");
    }

    private void scheduleReconnect() {
        plugin.getLogger().info("ServerEventStream 将在 " + (retryDelayMs.get() / 1000) + " 秒后重连...");
        retryExecutor.schedule(() -> {
            if (running) {
                connect();
            }
        }, retryDelayMs.get(), TimeUnit.MILLISECONDS);
        retryDelayMs.updateAndGet(d -> Math.min(d * 2, MAX_RETRY_DELAY_MS));
    }

    /**
     * 关闭事件流连接和重连调度器。
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

    /**
     * 发送事件到流中。线程安全，流断开时静默丢弃（等效于原 safeCall 语义）。
     *
     * @param event 事件请求
     */
    public void sendEvent(@NotNull ServerStatusProto.ServerEventStreamRequest event) {
        StreamObserver<ServerStatusProto.ServerEventStreamRequest> observer;
        synchronized (this) {
            observer = requestObserver;
        }
        if (observer == null) {
            return;
        }
        try {
            observer.onNext(event);
        } catch (IllegalStateException e) {
            plugin.getLogger().fine("Event dropped, stream already closed: "
                    + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
        } catch (io.grpc.StatusRuntimeException e) {
            plugin.getLogger().warning("gRPC transport error on send: " + e.getStatus());
        }
    }
}
