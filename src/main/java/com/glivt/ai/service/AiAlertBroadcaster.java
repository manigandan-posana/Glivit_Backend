package com.glivt.ai.service;

import com.glivt.ai.dto.AiEventDto;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AiAlertBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AiAlertBroadcaster.class);
    private final Map<Long, List<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long tenantId) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout
        List<SseEmitter> emitters = tenantEmitters.computeIfAbsent(tenantId, t -> new ArrayList<>());
        synchronized (emitters) {
            emitters.add(emitter);
        }

        emitter.onCompletion(() -> removeEmitter(tenantId, emitter));
        emitter.onTimeout(() -> removeEmitter(tenantId, emitter));
        emitter.onError(e -> removeEmitter(tenantId, emitter));

        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("AI Alert Stream Connected"));
        } catch (IOException e) {
            removeEmitter(tenantId, emitter);
        }

        return emitter;
    }

    public void broadcast(Long tenantId, AiEventDto event) {
        List<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        synchronized (emitters) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("AI_EVENT").data(event));
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
