package com.glivt.position;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tenant-scoped SSE fan-out for live vehicle positions. Mirrors the AI alert
 * broadcaster: emitters are grouped by tenant at subscription time, so a client
 * only ever receives positions for its own tenant. Dead emitters are pruned on
 * send failure. This holds no vehicle state — it is a pure push channel fed by
 * {@link LivePositionPublisher} after each committed ingest.
 */
@Service
public class LivePositionBroadcaster {

    /** Idle keep-alive window; clients auto-reconnect after it. */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final Map<Long, List<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long tenantId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        List<SseEmitter> emitters = tenantEmitters.computeIfAbsent(tenantId, t -> new ArrayList<>());
        synchronized (emitters) {
            emitters.add(emitter);
        }

        emitter.onCompletion(() -> removeEmitter(tenantId, emitter));
        emitter.onTimeout(() -> removeEmitter(tenantId, emitter));
        emitter.onError(e -> removeEmitter(tenantId, emitter));

        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("Live Position Stream Connected"));
        } catch (IOException e) {
            removeEmitter(tenantId, emitter);
        }

        return emitter;
    }

    public void broadcast(Long tenantId, LivePositionDto position) {
        List<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        synchronized (emitters) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("POSITION").data(position));
                } catch (Exception ex) {
                    deadEmitters.add(emitter);
                }
            }
            emitters.removeAll(deadEmitters);
        }
    }

    private void removeEmitter(Long tenantId, SseEmitter emitter) {
        List<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            synchronized (emitters) {
                emitters.remove(emitter);
            }
        }
    }
}
