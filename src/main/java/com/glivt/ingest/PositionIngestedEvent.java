package com.glivt.ingest;

import com.glivt.ai.dto.GpsFeatures;
import com.glivt.position.Position;

/**
 * Published after a valid position is committed. AI evaluation listens for this
 * AFTER_COMMIT so the heavy anomaly/LLM work never runs inside the ingestion
 * transaction and never blocks the device response.
 */
public record PositionIngestedEvent(Position position, GpsFeatures features, double speedLimitKph) {
}
