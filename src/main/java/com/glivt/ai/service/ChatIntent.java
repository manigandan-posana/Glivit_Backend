package com.glivt.ai.service;

import java.util.List;
import java.util.Locale;

/**
 * What the user is asking about.
 *
 * <p>Intent decides which slice of fleet data is retrieved. Sending the whole
 * database on every question would be slow, would blow the model's context
 * window, and would widen the blast radius of any prompt-injection attempt - so
 * only the records relevant to the detected intent are fetched.
 *
 * <p>Detection is deliberately a keyword classifier rather than an LLM call: it
 * must work when Ollama is down (that is exactly when the deterministic answer
 * matters most) and it must not add a second model round-trip per question.
 */
public enum ChatIntent {

    FLEET_STATUS(List.of("vehicles", "vehicle list", "permitted vehicles", "my vehicles", "list of vehicles", "vehicle", "how many running", "running", "idle", "stopped", "offline", "online", "fleet status", "how many vehicles", "overview", "summary", "dashboard", "fleet health", "offline vehicles")),
    VEHICLE_STATUS(List.of("vehicle status", "this vehicle", "selected vehicle", "status of", "is it running", "condition")),
    CURRENT_LOCATION(List.of("tracking", "live location", "live tracking", "real-time", "real time", "where is", "location", "current position", "located", "whereabouts", "last seen", "position of")),
    RECENT_ALERTS(List.of("alerts", "alert", "anomaly", "anomalies", "incident", "incidents", "warning", "event", "events", "notification")),
    MAINTENANCE(List.of("maintenance", "service", "repair", "breakdown", "battery", "odometer", "engine hour", "due for", "due for service")),
    DRIVER_SAFETY(List.of("drivers", "driver", "safety score", "harsh", "braking", "coaching", "behaviour", "behavior", "risky", "driver score", "driver list", "safety scores")),
    FUEL(List.of("fuel", "diesel", "petrol", "consumption", "mileage", "efficiency", "charge", "battery level")),
    ROUTE_HISTORY(List.of("trips", "trip", "journey", "history", "playback", "travelled", "traveled", "deviation", "distance covered", "route history", "route")),
    ETA(List.of("eta", "arrive", "arrival", "how long", "when will", "reach")),
    DISPATCH(List.of("dispatch", "assign", "nearest vehicle", "which vehicle should", "send a vehicle", "closest")),
    REPORT_SUMMARY(List.of("report", "reports", "export", "statement", "summary of")),
    GEOFENCE(List.of("geofences", "geofence", "zone", "boundary", "area", "site", "how create geofence")),
    DEVICE_HEALTH(List.of("device", "gps device", "tracker", "signal", "offline device", "not reporting", "imei", "devices", "gps devices")),
    APP_HELP(List.of("how do i", "how to", "where can i", "help", "guide", "tutorial", "which screen", "feature", "what can i do here")),
    
    // New modules
    USERS(List.of("users", "user", "operator", "operators", "team", "members")),
    PROJECTS(List.of("projects", "project", "assignment", "assignments")),
    COMMANDS(List.of("commands", "command", "engine cut", "immobilise", "immobilize", "lock", "unlock")),
    TENANTS(List.of("tenant", "tenants", "organisation", "organization", "company")),
    
    UNKNOWN(List.of());

    private final List<String> keywords;

    ChatIntent(List<String> keywords) {
        this.keywords = keywords;
    }

    /**
     * Classify a question. The longest matching keyword wins, so "how many
     * vehicles" beats the generic "how many" and "where is" beats "help".
     */
    public static ChatIntent detect(String message) {
        if (message == null || message.isBlank()) {
            return UNKNOWN;
        }
        String normalized = message.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim();

        // 1. Prioritized exact keyword checks to resolve compound/ambiguous queries
        if (normalized.contains("help") || normalized.contains("guide") || normalized.contains("tutorial") || normalized.contains("how do i") || normalized.contains("how to") || normalized.contains("how can i")) {
            return APP_HELP;
        }
        if (normalized.contains("location") || normalized.contains("locate") || normalized.contains("where") || normalized.contains("track") || normalized.contains("gps") || normalized.contains("map")) {
            return CURRENT_LOCATION;
        }
        if (normalized.contains("score") || normalized.contains("safety") || normalized.contains("coaching") || normalized.contains("behavior") || normalized.contains("behaviour") || normalized.contains("harsh")) {
            return DRIVER_SAFETY;
        }
        if (normalized.contains("geofence") || normalized.contains("zone") || normalized.contains("boundary")) {
            return GEOFENCE;
        }
        if (normalized.contains("maintenance") || normalized.contains("service") || normalized.contains("repair") || normalized.contains("due for") || normalized.contains("odometer") || normalized.contains("battery")) {
            return MAINTENANCE;
        }
        if (normalized.contains("alert") || normalized.contains("anomaly") || normalized.contains("incident") || normalized.contains("event") || normalized.contains("warning")) {
            return RECENT_ALERTS;
        }
        if (normalized.contains("trip") || normalized.contains("journey") || normalized.contains("playback") || normalized.contains("history") || normalized.contains("route")) {
            return ROUTE_HISTORY;
        }
        if (normalized.contains("command") || normalized.contains("lock") || normalized.contains("unlock") || normalized.contains("immobilise") || normalized.contains("immobilize") || normalized.contains("engine cut")) {
            return COMMANDS;
        }
        if (normalized.contains("user") || normalized.contains("operator") || normalized.contains("team") || normalized.contains("member")) {
            return USERS;
        }
        if (normalized.contains("project") || normalized.contains("projects") || normalized.contains("assignment")) {
            return PROJECTS;
        }
        if (normalized.contains("tenant") || normalized.contains("organization") || normalized.contains("organisation") || normalized.contains("company")) {
            return TENANTS;
        }
        if (normalized.contains("report") || normalized.contains("reports") || normalized.contains("export") || normalized.contains("statement")) {
            return REPORT_SUMMARY;
        }
        if (normalized.contains("fuel") || normalized.contains("diesel") || normalized.contains("petrol") || normalized.contains("mileage")) {
            return FUEL;
        }
        if (normalized.contains("eta") || normalized.contains("arrive") || normalized.contains("arrival")) {
            return ETA;
        }
        if (normalized.contains("dispatch") || normalized.contains("assign") || normalized.contains("nearest")) {
            return DISPATCH;
        }

        // 2. Lexical keyword fallback matching
        String[] words = normalized.split("\\s+");

        // Check multi-word keywords
        ChatIntent best = UNKNOWN;
        int bestLength = 0;
        for (ChatIntent intent : values()) {
            for (String keyword : intent.keywords) {
                if (keyword.contains(" ")) { // multi-word phrase
                    if (normalized.contains(keyword) && keyword.length() > bestLength) {
                        best = intent;
                        bestLength = keyword.length();
                    }
                }
            }
        }
        if (best != UNKNOWN) {
            return best;
        }

        // If no multi-word match, check individual words
        for (String word : words) {
            String stem = word;
            if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 2) {
                stem = word.substring(0, word.length() - 1);
            }

            for (ChatIntent intent : values()) {
                for (String keyword : intent.keywords) {
                    if (!keyword.contains(" ")) { // single-word keyword
                        String kwStem = keyword;
                        if (keyword.endsWith("s") && !keyword.endsWith("ss") && keyword.length() > 2) {
                            kwStem = keyword.substring(0, keyword.length() - 1);
                        }
                        if (stem.equals(kwStem) || word.equals(keyword)) {
                            return intent;
                        }
                    }
                }
            }
        }

        return UNKNOWN;
    }

    /** True when the intent needs a specific vehicle to be meaningful. */
    public boolean needsVehicle() {
        return this == VEHICLE_STATUS || this == CURRENT_LOCATION || this == ETA || this == ROUTE_HISTORY;
    }
}
