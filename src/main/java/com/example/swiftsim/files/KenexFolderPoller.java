package com.example.swiftsim.files;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls the payment-application outgoing folders. Idle, and does not touch those folders, while mode is HTTP. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KenexFolderPoller {
    private final FileTransportService files;
    private final TaskScheduler scheduler;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean stopped;

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void start() {
        schedule(500);
    }

    private void schedule(long delayMs) {
        if (stopped) return;
        scheduler.schedule(this::tick, Instant.now().plusMillis(Math.max(50, delayMs)));
    }

    private void tick() {
        if (stopped) return;
        if (!busy.compareAndSet(false, true)) {
            schedule(200);
            return;
        }
        long wait = 2000;
        try {
            wait = files.pollOnce();
        } catch (Exception e) {
            log.warn("file transport poll failed: {}", e.toString());
        } finally {
            busy.set(false);
            if (!stopped) schedule(wait);
        }
    }

    @PreDestroy
    public void shutdown() {
        stopped = true;
        files.stop();
        log.info("file transport poller stopped");
    }
}
