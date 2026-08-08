package com.glivt.ingest;

/**
 * Published after a valid position is committed, purely so the live map can
 * stream it.
 *
 * <p>AI evaluation deliberately does NOT listen for this: it is driven by the
 * durable {@code ai_position_outbox} instead, so an in-memory event listener can
 * never be the reason an anomaly is missed after a restart, and a slow AI stack
 * can never back up onto the ingestion thread.
 *
 * @param outOfOrder true when the packet did not advance the live snapshot, so
 *                   subscribers must not move the marker backwards
 */
public record PositionIngestedEvent(Long tenantId, Long deviceId, Long positionId,
        boolean outOfOrder) {
}
