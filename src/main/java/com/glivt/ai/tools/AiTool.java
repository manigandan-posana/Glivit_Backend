package com.glivt.ai.tools;

import com.glivt.security.AppUserPrincipal;
import java.util.List;
import java.util.Map;

/**
 * A read-only, tenant-scoped data accessor the assistant may call.
 *
 * <p>This is what replaces keyword matching. The model is given every tool's
 * name, description and argument schema and decides for itself which to call, so
 * a question phrased as "vehicle list", "show me the trucks" or "what am I
 * running today" all reach the same accessor without anyone maintaining a
 * synonym list.
 *
 * <p>Three invariants hold for every implementation:
 * <ul>
 *   <li><b>Read-only.</b> A tool never mutates state. Anything that changes the
 *       fleet stays behind the normal API with its own permission check and an
 *       explicit user confirmation.</li>
 *   <li><b>Tenant-scoped.</b> Every query is filtered by the tenant on the
 *       authenticated principal, never by an argument the model supplied.</li>
 *   <li><b>Permission-checked.</b> {@link #requiredPermission()} is enforced
 *       before execution, so the assistant can never widen a user's access.</li>
 * </ul>
 */
public interface AiTool {

    /** Stable identifier the model calls. Lower snake_case. */
    String name();

    /** What the tool returns, in plain language. This is what the model matches on. */
    String description();

    /** JSON-schema properties for the arguments, may be empty. */
    default Map<String, Object> parameters() {
        return Map.of();
    }

    /** Argument names that must be supplied. */
    default List<String> requiredParameters() {
        return List.of();
    }

    /** Permission key required to run this tool. */
    String requiredPermission();

    /**
     * Execute the tool for this user.
     *
     * @param arguments model-supplied arguments; treat every value as untrusted
     * @return a JSON-serialisable result, shown to the model as data
     */
    Object execute(AppUserPrincipal user, Map<String, Object> arguments);
}
