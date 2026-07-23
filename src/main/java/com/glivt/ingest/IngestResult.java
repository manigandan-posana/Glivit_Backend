package com.glivt.ingest;

/** Outcome returned to the device after an ingestion attempt. */
public record IngestResult(
        boolean accepted,
        boolean duplicate,
        String state,
        double gpsConfidence,
        Long positionId) {
}
