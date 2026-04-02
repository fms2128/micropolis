package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured activity logger that records every turn, tool call, reward signal,
 * and game event to a JSONL file for post-game analysis and agent optimization.
 *
 * Log format: one JSON object per line in ai_data/activity_log.jsonl
 * Each line has a "type" field: "turn_start", "tool_call", "event", "turn_end"
 */
public class ActivityLogger {

    private static final String LOG_DIR = "ai_data";
    private static final String LOG_FILE = LOG_DIR + "/activity_log.jsonl";

    private Path logPath;
    private int currentTurn = 0;
    private final List<JsonObject> pendingToolCalls = new ArrayList<>();
    private JsonObject turnStartState;

    public ActivityLogger() {
        try {
            Path dir = Paths.get(LOG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            this.logPath = Paths.get(LOG_FILE);
        } catch (IOException e) {
            System.err.println("ActivityLogger: failed to init: " + e.getMessage());
        }
    }

    public void reset() {
        try {
            if (logPath != null && Files.exists(logPath)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path archive = Paths.get(LOG_DIR + "/activity_log_" + timestamp + ".jsonl");
                Files.move(logPath, archive, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // best effort archive
        }
        currentTurn = 0;
        pendingToolCalls.clear();
        turnStartState = null;
    }

    /**
     * Log the start of a turn with a full game state snapshot.
     */
    public void logTurnStart(int turn, JsonObject gameState, String systemEvents) {
        this.currentTurn = turn;
        this.pendingToolCalls.clear();

        turnStartState = new JsonObject();
        turnStartState.addProperty("type", "turn_start");
        turnStartState.addProperty("turn", turn);
        turnStartState.addProperty("timestamp", Instant.now().toString());
        turnStartState.add("state", gameState);
        if (systemEvents != null && !systemEvents.isEmpty()) {
            turnStartState.addProperty("system_events", systemEvents);
        }
        writeLine(turnStartState);
    }

    /**
     * Log a tool call with its input parameters, result, and timing.
     */
    public void logToolCall(String toolName, JsonObject input, JsonObject result,
                            long durationMs, int fundsBefore, int fundsAfter,
                            int scoreBefore, int popBefore) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "tool_call");
        entry.addProperty("turn", currentTurn);
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("tool", toolName);
        entry.add("input", input);
        entry.add("result", result);
        entry.addProperty("duration_ms", durationMs);
        entry.addProperty("funds_before", fundsBefore);
        entry.addProperty("funds_after", fundsAfter);
        entry.addProperty("funds_delta", fundsAfter - fundsBefore);
        entry.addProperty("score_at_call", scoreBefore);
        entry.addProperty("pop_at_call", popBefore);

        boolean success = result.has("success") && result.get("success").getAsBoolean();
        entry.addProperty("success", success);

        pendingToolCalls.add(entry);
        writeLine(entry);
    }

    /**
     * Log the end of a turn with reward signal and post-turn state.
     */
    public void logTurnEnd(int turn, JsonObject postState, JsonObject rewardBreakdown,
                           List<String> activeObjectives, String agentResponse) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "turn_end");
        entry.addProperty("turn", turn);
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("tool_calls_count", pendingToolCalls.size());

        int succeeded = 0, failed = 0;
        for (JsonObject tc : pendingToolCalls) {
            if (tc.has("success") && tc.get("success").getAsBoolean()) succeeded++;
            else failed++;
        }
        entry.addProperty("tools_succeeded", succeeded);
        entry.addProperty("tools_failed", failed);

        JsonArray toolSummary = new JsonArray();
        for (JsonObject tc : pendingToolCalls) {
            JsonObject brief = new JsonObject();
            brief.addProperty("tool", tc.get("tool").getAsString());
            brief.addProperty("success", tc.get("success").getAsBoolean());
            brief.addProperty("funds_delta", tc.get("funds_delta").getAsInt());
            toolSummary.add(brief);
        }
        entry.add("tool_summary", toolSummary);

        if (postState != null) entry.add("state_after", postState);
        if (rewardBreakdown != null) entry.add("reward", rewardBreakdown);

        if (activeObjectives != null && !activeObjectives.isEmpty()) {
            JsonArray objs = new JsonArray();
            activeObjectives.forEach(objs::add);
            entry.add("objectives", objs);
        }

        if (agentResponse != null && !agentResponse.isEmpty()) {
            String truncated = agentResponse.length() > 500
                ? agentResponse.substring(0, 500) + "..." : agentResponse;
            entry.addProperty("agent_text", truncated);
        }

        writeLine(entry);
        pendingToolCalls.clear();
    }

    /**
     * Log a game event (disaster, milestone, demand shift, etc.)
     */
    public void logEvent(String category, String message) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "event");
        entry.addProperty("turn", currentTurn);
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("category", category);
        entry.addProperty("message", message);
        writeLine(entry);
    }

    /**
     * Log a game engine snapshot (census, evaluation, demand change, funds change, etc.)
     */
    public void logSnapshot(String snapshotType, JsonObject data) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "snapshot");
        entry.addProperty("turn", currentTurn);
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("snapshot_type", snapshotType);
        entry.add("data", data);
        writeLine(entry);
    }

    /**
     * Log the LLM's full text response for a turn.
     */
    public void logAgentThinking(int turn, String thinking) {
        if (thinking == null || thinking.isEmpty()) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "agent_thinking");
        entry.addProperty("turn", turn);
        entry.addProperty("timestamp", Instant.now().toString());
        String truncated = thinking.length() > 2000
            ? thinking.substring(0, 2000) + "..." : thinking;
        entry.addProperty("text", truncated);
        writeLine(entry);
    }

    public Path getLogPath() {
        return logPath;
    }

    private void writeLine(JsonObject json) {
        if (logPath == null) return;
        try {
            String line = json.toString() + System.lineSeparator();
            Files.write(logPath, line.getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ActivityLogger write error: " + e.getMessage());
        }
    }
}
