package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import micropolisj.engine.Micropolis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Orchestrates the AI assistant: collects state, sends requests to Claude,
 * executes tool calls, and manages the conversation loop.
 */
public class AIAssistant {

    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private Micropolis engine;
    private GameStateObserver observer;
    private AIToolHandler toolHandler;
    private AIGameListener listener;
    private final LLMClient client;

    private final List<JsonObject> conversationHistory = new ArrayList<>();
    private final List<Objective> currentObjectives = new ArrayList<>();
    private final List<Objective> completedObjectives = new ArrayList<>();
    private Consumer<String> onThinking;
    private Consumer<String> onAction;
    private Consumer<String> onError;
    private Consumer<String> onObjectivesChanged;
    private Runnable budgetDismisser;
    private BooleanSupplier budgetDialogOpenChecker;

    public static class Objective {
        private final String text;
        private final List<String> linkedMessageIds;
        private boolean completed;

        public Objective(String text) {
            this(text, List.of());
        }

        public Objective(String text, List<String> linkedMessageIds) {
            this.text = text;
            this.linkedMessageIds = new ArrayList<>(linkedMessageIds);
            this.completed = false;
        }

        public String getText() { return text; }
        public boolean isCompleted() { return completed; }
        public List<String> getLinkedMessageIds() { return linkedMessageIds; }
    }

    public static class ActionRecord {
        final int turn;
        final String toolName;
        final String inputSummary;
        final int scoreBefore;
        final int popBefore;
        final int fundsBefore;

        ActionRecord(int turn, String toolName, String inputSummary,
                     int scoreBefore, int popBefore, int fundsBefore) {
            this.turn = turn;
            this.toolName = toolName;
            this.inputSummary = inputSummary;
            this.scoreBefore = scoreBefore;
            this.popBefore = popBefore;
            this.fundsBefore = fundsBefore;
        }
    }

    private static final int MAX_ACTION_HISTORY = 10;
    private static final int REFLECTION_INTERVAL = 10;
    private static final int MAX_REWARD_HISTORY = 20;
    private final LinkedList<ActionRecord> actionHistory = new LinkedList<>();
    private final LinkedList<Double> rewardHistory = new LinkedList<>();
    private ReflectiveAgent reflectiveAgent;
    private final ActivityLogger activityLogger = new ActivityLogger();

    private final ConcurrentLinkedQueue<String> pendingUserMessages = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private volatile boolean autoPlayActive = false;
    private volatile int nextTurnDelayMs = 10_000;
    private int turnCount = 0;

    public AIAssistant() {
        this.client = new LLMClient();
        this.reflectiveAgent = new ReflectiveAgent(client);
    }

    public void setEngine(Micropolis engine) {
        if (this.engine != null && this.listener != null) {
            this.engine.removeListener(this.listener);
        }
        this.engine = engine;
        this.observer = new GameStateObserver(engine);
        this.toolHandler = new AIToolHandler(engine, observer, this);
        this.listener = new AIGameListener();
        this.listener.setActivityLogger(activityLogger);
        this.listener.setEngine(engine);
        engine.addListener(this.listener);
    }

    public LLMClient getClient() {
        return client;
    }

    public void setOnThinking(Consumer<String> onThinking) {
        this.onThinking = onThinking;
        if (reflectiveAgent != null) {
            reflectiveAgent.setOnThinking(onThinking);
        }
    }

    public void setOnAction(Consumer<String> onAction) {
        this.onAction = onAction;
    }

    public void setOnError(Consumer<String> onError) {
        this.onError = onError;
    }

    public void setBudgetDismisser(Runnable dismisser) {
        this.budgetDismisser = dismisser;
    }

    public void setBudgetDialogOpenChecker(BooleanSupplier checker) {
        this.budgetDialogOpenChecker = checker;
    }

    public boolean dismissBudgetDialog() {
        if (budgetDismisser != null && isBudgetDialogOpen()) {
            budgetDismisser.run();
            return true;
        }
        return false;
    }

    public boolean isBudgetDialogOpen() {
        return budgetDialogOpenChecker != null && budgetDialogOpenChecker.getAsBoolean();
    }

    public void setAutoPlayActive(boolean active) {
        this.autoPlayActive = active;
    }

    public boolean isAutoPlayActive() {
        return autoPlayActive;
    }

    public void setNextTurnDelayMs(int delayMs) {
        this.nextTurnDelayMs = Math.max(0, delayMs);
    }

    public int getNextTurnDelayMs() {
        return nextTurnDelayMs;
    }

    public AIGameListener getListener() {
        return listener;
    }

    public LinkedList<Double> getRewardHistory() {
        return rewardHistory;
    }

    public GameStateObserver getObserver() {
        return observer;
    }

    public ActivityLogger getActivityLogger() {
        return activityLogger;
    }

    public boolean isConfigured() {
        return client.isConfigured() && engine != null;
    }

    /**
     * Run one AI turn: observe state, consult Claude, execute tool calls.
     * Returns a future that completes when the turn is done.
     */
    public CompletableFuture<Void> runTurn() {
        return runTurn(null);
    }

    /**
     * Run one AI turn with an optional user message.
     */
    public CompletableFuture<Void> runTurn(String userMessage) {
        if (!isConfigured()) {
            emit(onError, "AI not configured. Set API key and ensure game is loaded.");
            return CompletableFuture.completedFuture(null);
        }
        if (running) {
            emit(onError, "AI is already thinking...");
            return CompletableFuture.completedFuture(null);
        }

        running = true;
        turnCount++;

        return CompletableFuture.runAsync(() -> {
            try {
                executeTurn(userMessage);
            } catch (Exception e) {
                emit(onError, "Error: " + e.getMessage());
            } finally {
                running = false;
            }
        });
    }

    private void executeTurn(String userMessage) throws Exception {
        boolean hasCritical = listener.hasCriticalEvent();
        nextTurnDelayMs = hasCritical ? 0 : 10_000;
        JsonObject summary = observer.getMinimalSummary();
        String summaryJson = summary.toString();

        JsonObject preState = new JsonObject();
        preState.addProperty("population", engine.getCityPopulation());
        preState.addProperty("funds", engine.getBudget().getTotalFunds());
        preState.addProperty("score", engine.getEvaluation().getCityScore());
        preState.addProperty("res_demand", engine.getResValve());
        preState.addProperty("com_demand", engine.getComValve());
        preState.addProperty("ind_demand", engine.getIndValve());
        preState.addProperty("powered_zones", engine.getPoweredZoneCount());
        preState.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());
        preState.addProperty("crime", engine.getCrimeAverage());
        preState.addProperty("pollution", engine.getPollutionAverage());
        preState.addProperty("traffic", engine.getTrafficAverage());
        preState.addProperty("road_effect", engine.getRoadEffect());

        if (summary.has("reward")) {
            rewardHistory.addLast(summary.get("reward").getAsDouble());
            while (rewardHistory.size() > MAX_REWARD_HISTORY) {
                rewardHistory.removeFirst();
            }
        }
        String structuredEvents = listener.drainStructuredMessages();
        listener.clearCriticalEvent();

        activityLogger.logTurnStart(turnCount, preState, structuredEvents);

        boolean hasUrgentMessages = structuredEvents.contains("[CRITICAL]")
            || structuredEvents.contains("[DISASTER]");
        boolean hasDemandMessages = structuredEvents.contains("[DEMAND]")
            || structuredEvents.contains("[PROBLEM]");

        StringBuilder userContent = new StringBuilder();
        userContent.append("[Turn ").append(turnCount).append("] ").append(summaryJson);

        if (!structuredEvents.isEmpty()) {
            userContent.append("\n\n[System Updates — READ THESE FIRST]\n");
            userContent.append(structuredEvents);
            userContent.append("[End System Updates]");
        }

        java.util.Set<String> coveredMessageIds = new java.util.HashSet<>();
        if (!currentObjectives.isEmpty() || !completedObjectives.isEmpty()) {
            userContent.append("\n\n[Current Objectives]");
            for (int i = 0; i < currentObjectives.size(); i++) {
                Objective obj = currentObjectives.get(i);
                userContent.append("\n").append(i + 1).append(". ").append(obj.getText());
                if (!obj.getLinkedMessageIds().isEmpty()) {
                    userContent.append(" (covers: ").append(String.join(",", obj.getLinkedMessageIds())).append(")");
                    coveredMessageIds.addAll(obj.getLinkedMessageIds());
                }
            }
            if (!completedObjectives.isEmpty()) {
                userContent.append("\n[Recently Completed]");
                for (Objective obj : completedObjectives) {
                    userContent.append("\n- [DONE] ").append(obj.getText());
                    coveredMessageIds.addAll(obj.getLinkedMessageIds());
                }
            }
            userContent.append("\n[End Objectives]");
        }

        if (!coveredMessageIds.isEmpty() && !structuredEvents.isEmpty()) {
            List<String> allMsgIds = listener.getCurrentMessageIds();
            List<String> uncoveredIds = new ArrayList<>();
            for (String id : allMsgIds) {
                if (!coveredMessageIds.contains(id)) {
                    uncoveredIds.add(id);
                }
            }
            if (!uncoveredIds.isEmpty()) {
                userContent.append("\n[UNCOVERED Messages — these need new objectives: ")
                    .append(String.join(", ", uncoveredIds)).append("]");
            }
        }

        // Infrastructure diagnostics removed from auto-inject.
        // Agent uses get_city_entities tool on demand instead.

        String actionHist = formatActionHistory();
        if (!actionHist.isEmpty()) {
            userContent.append("\n").append(actionHist);
        }

        List<String> allUserMessages = new ArrayList<>();
        String queued;
        while ((queued = pendingUserMessages.poll()) != null) {
            allUserMessages.add(queued);
        }
        if (userMessage != null && !userMessage.isEmpty()) {
            allUserMessages.add(userMessage);
        }
        if (!allUserMessages.isEmpty()) {
            userContent.append("\n\n[USER MESSAGES — MUST become objectives]\n");
            for (String msg : allUserMessages) {
                userContent.append(">> ").append(msg).append("\n");
                activityLogger.logEvent("USER_MESSAGE", msg);
            }
            userContent.append("You MUST create or update objectives to address each user message above.\n");
            userContent.append("[End User Messages]");
        }

        if (hasUrgentMessages) {
            userContent.append("\n\n!! URGENT events detected in System Updates above. Address these first.");
        }

        if (!rewardHistory.isEmpty()) {
            userContent.append("\n\n[Reward Trend] ");
            int show = Math.min(rewardHistory.size(), 5);
            userContent.append("Last ").append(show).append(": ");
            for (int i = rewardHistory.size() - show; i < rewardHistory.size(); i++) {
                if (i > rewardHistory.size() - show) userContent.append(", ");
                userContent.append(String.format("%.1f", rewardHistory.get(i)));
            }
            double avg = rewardHistory.stream().mapToDouble(d -> d).average().orElse(0);
            userContent.append(String.format(" (avg: %.1f)", avg));
        }

        userContent.append("\n\nRemember to call end_turn('continue' or 'wait') when you're done.");

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray userContentArr = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", userContent.toString());
        userContentArr.add(textBlock);
        userMsg.add("content", userContentArr);

        conversationHistory.add(userMsg);
        trimHistory();

        JsonArray messages = new JsonArray();
        for (JsonObject msg : conversationHistory) {
            messages.add(msg);
        }

        JsonArray tools = toolHandler.getToolDefinitions();
        int toolRounds = 0;

        String systemPrompt = buildSystemPrompt();

        while (toolRounds < MAX_TOOL_ROUNDS) {
            emit(onThinking, toolRounds == 0 ? "Thinking..." : "Executing tools (round " + (toolRounds + 1) + ")...");

            JsonObject response = client.sendRequest(systemPrompt, messages, tools);
            String stopReason = response.has("stop_reason") ? response.get("stop_reason").getAsString() : "end_turn";

            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.add("content", response.getAsJsonArray("content"));
            messages.add(assistantMsg);
            conversationHistory.add(assistantMsg);

            String text = LLMClient.extractText(response);
            if (!text.isEmpty()) {
                emit(onThinking, text);
                activityLogger.logAgentThinking(turnCount, text);
            }

            if (!"tool_use".equals(stopReason) || !LLMClient.hasToolUse(response)) {
                break;
            }

            JsonArray toolResults = new JsonArray();
            for (JsonElement el : response.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                if (!"tool_use".equals(block.get("type").getAsString())) continue;

                String toolId = block.get("id").getAsString();
                String toolName = block.get("name").getAsString();
                JsonObject input = block.getAsJsonObject("input");

                emit(onAction, "Tool: " + toolName + " " + input.toString());

                JsonObject result = toolHandler.executeTool(toolName, input);

                if (result.has("_image_base64")) {
                    emit(onAction, "Result: [screenshot captured]");
                } else {
                    emit(onAction, "Result: " + result.toString());
                }

                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", toolId);

                if (result.has("_image_base64")) {
                    String imgBase64 = result.remove("_image_base64").getAsString();
                    String mediaType = result.has("_image_media_type")
                        ? result.remove("_image_media_type").getAsString() : "image/png";

                    JsonArray contentBlocks = new JsonArray();

                    JsonObject imgBlock = new JsonObject();
                    imgBlock.addProperty("type", "image");
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", mediaType);
                    source.addProperty("data", imgBase64);
                    imgBlock.add("source", source);
                    contentBlocks.add(imgBlock);

                    JsonObject txtBlock = new JsonObject();
                    txtBlock.addProperty("type", "text");
                    txtBlock.addProperty("text", result.toString());
                    contentBlocks.add(txtBlock);

                    toolResult.add("content", contentBlocks);
                } else {
                    toolResult.addProperty("content", result.toString());
                }
                toolResults.add(toolResult);
            }

            JsonObject toolResultMsg = new JsonObject();
            toolResultMsg.addProperty("role", "user");
            toolResultMsg.add("content", toolResults);
            messages.add(toolResultMsg);
            conversationHistory.add(toolResultMsg);

            toolRounds++;
        }

        // Log turn end with post-state, reward breakdown, and objectives
        JsonObject postState = new JsonObject();
        postState.addProperty("population", engine.getCityPopulation());
        postState.addProperty("funds", engine.getBudget().getTotalFunds());
        postState.addProperty("score", engine.getEvaluation().getCityScore());
        postState.addProperty("res_demand", engine.getResValve());
        postState.addProperty("com_demand", engine.getComValve());
        postState.addProperty("ind_demand", engine.getIndValve());
        postState.addProperty("powered_zones", engine.getPoweredZoneCount());
        postState.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());
        postState.addProperty("crime", engine.getCrimeAverage());
        postState.addProperty("pollution", engine.getPollutionAverage());
        postState.addProperty("traffic", engine.getTrafficAverage());
        postState.addProperty("road_effect", engine.getRoadEffect());

        JsonObject rewardInfo = null;
        if (summary.has("reward")) {
            rewardInfo = new JsonObject();
            if (summary.has("reward_score")) rewardInfo.addProperty("score_component", summary.get("reward_score").getAsDouble());
            if (summary.has("reward_pop")) rewardInfo.addProperty("pop_component", summary.get("reward_pop").getAsDouble());
            if (summary.has("reward_funds")) rewardInfo.addProperty("funds_component", summary.get("reward_funds").getAsDouble());
            if (summary.has("reward_structural")) rewardInfo.addProperty("structural_component", summary.get("reward_structural").getAsDouble());
            rewardInfo.addProperty("instant", summary.get("reward").getAsDouble());
            if (summary.has("reward_trend")) rewardInfo.addProperty("trend", summary.get("reward_trend").getAsDouble());
        }

        List<String> objTexts = new ArrayList<>();
        for (Objective obj : currentObjectives) objTexts.add(obj.getText());

        activityLogger.logTurnEnd(turnCount, postState, rewardInfo, objTexts, null);

        trimHistory();

        // Reflection loop disabled — agent plays freely without meta-agent interference.
        // To re-enable, uncomment the block below.
        /*
        if (turnCount % REFLECTION_INTERVAL == 0 && turnCount > 0) {
            try {
                reflectiveAgent.runReflection(
                    conversationHistory,
                    currentObjectives,
                    completedObjectives,
                    rewardHistory,
                    observer,
                    turnCount
                );
            } catch (Exception e) {
                emit(onError, "Reflection error: " + e.getMessage());
            }
        }
        */
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.remove(0);
        }
        // Ensure history starts with a plain user message (not tool results)
        // and that no assistant tool_call message is left without its
        // corresponding tool_result follow-up.
        while (!conversationHistory.isEmpty()) {
            JsonObject first = conversationHistory.get(0);
            String role = first.get("role").getAsString();
            if ("user".equals(role) && !isToolResultMessage(first)) {
                break;
            }
            conversationHistory.remove(0);
        }
        // If the last message is an assistant message containing tool_use blocks,
        // remove it (and any preceding orphaned assistant/tool_result chain) so
        // the API never sees a tool_call without matching tool results.
        while (!conversationHistory.isEmpty()) {
            JsonObject last = conversationHistory.get(conversationHistory.size() - 1);
            if ("assistant".equals(last.get("role").getAsString()) && hasToolUseBlocks(last)) {
                conversationHistory.remove(conversationHistory.size() - 1);
            } else {
                break;
            }
        }
    }

    private boolean isToolResultMessage(JsonObject msg) {
        JsonElement content = msg.get("content");
        if (content == null || !content.isJsonArray()) return false;
        for (JsonElement el : content.getAsJsonArray()) {
            if (el.isJsonObject() && "tool_result".equals(
                    el.getAsJsonObject().has("type") ? el.getAsJsonObject().get("type").getAsString() : "")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasToolUseBlocks(JsonObject msg) {
        JsonElement content = msg.get("content");
        if (content == null || !content.isJsonArray()) return false;
        for (JsonElement el : content.getAsJsonArray()) {
            if (el.isJsonObject() && "tool_use".equals(
                    el.getAsJsonObject().has("type") ? el.getAsJsonObject().get("type").getAsString() : "")) {
                return true;
            }
        }
        return false;
    }

    private static final String STRATEGY_FILE = "ai_data/game_strategy_guide.md";

    private String buildSystemPrompt() {
        if (turnCount <= 1) {
            try {
                Path p = Paths.get(STRATEGY_FILE);
                if (Files.exists(p)) {
                    String guide = Files.readString(p);
                    return SystemPrompt.PROMPT
                        + "\n\n# Game Strategy Guide (Engine Mechanics Reference)\n\n"
                        + guide;
                }
            } catch (IOException ignored) {}
        }
        return SystemPrompt.PROMPT;
    }

    public boolean shouldTrigger(int simStepsSinceLastTurn, int triggerInterval) {
        if (listener.hasCriticalEvent()) return true;
        return simStepsSinceLastTurn >= triggerInterval;
    }

    public boolean isRunning() {
        return running;
    }

    public void clearHistory() {
        conversationHistory.clear();
        currentObjectives.clear();
        completedObjectives.clear();
        actionHistory.clear();
        rewardHistory.clear();
        turnCount = 0;
        activityLogger.reset();
        fireObjectivesChanged();
        try {
            Path sessionPath = Paths.get("ai_data/session_notes.md");
            if (Files.exists(sessionPath)) Files.delete(sessionPath);
            Path reflectivePath = Paths.get("ai_data/reflective_prompt.md");
            if (Files.exists(reflectivePath)) Files.delete(reflectivePath);
            Path reflectionLog = Paths.get("ai_data/reflection_log.md");
            if (Files.exists(reflectionLog)) Files.delete(reflectionLog);
        } catch (IOException e) {
            // ignore
        }
    }

    public List<Objective> getObjectives() {
        return new ArrayList<>(currentObjectives);
    }

    public List<Objective> getCompletedObjectives() {
        return new ArrayList<>(completedObjectives);
    }

    public void setObjectives(List<String> objectives) {
        setObjectives(objectives, null);
    }

    public void setObjectives(List<String> objectives, List<List<String>> linkedMessageIds) {
        currentObjectives.clear();
        int limit = Math.min(objectives.size(), 5);
        for (int i = 0; i < limit; i++) {
            String obj = objectives.get(i).trim();
            if (!obj.isEmpty()) {
                List<String> msgIds = (linkedMessageIds != null && i < linkedMessageIds.size())
                    ? linkedMessageIds.get(i) : List.of();
                currentObjectives.add(new Objective(obj, msgIds));
            }
        }
        fireObjectivesChanged();

        JsonArray objArr = new JsonArray();
        for (Objective o : currentObjectives) objArr.add(o.getText());
        JsonObject snapshot = new JsonObject();
        snapshot.add("objectives", objArr);
        snapshot.addProperty("count", currentObjectives.size());
        activityLogger.logSnapshot("OBJECTIVES_SET", snapshot);
    }

    public boolean completeObjective(int index) {
        if (index < 0 || index >= currentObjectives.size()) {
            return false;
        }
        Objective obj = currentObjectives.remove(index);
        obj.completed = true;
        completedObjectives.add(obj);
        fireObjectivesChanged();

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("completed_objective", obj.getText());
        snapshot.addProperty("remaining_active", currentObjectives.size());
        snapshot.addProperty("total_completed", completedObjectives.size());
        activityLogger.logSnapshot("OBJECTIVE_COMPLETED", snapshot);

        return true;
    }

    public void recordAction(String toolName, String inputSummary) {
        if (engine == null) return;
        ActionRecord rec = new ActionRecord(
            turnCount, toolName, inputSummary,
            engine.getEvaluation().getCityScore(),
            engine.getCityPopulation(),
            engine.getBudget().getTotalFunds()
        );
        actionHistory.addLast(rec);
        while (actionHistory.size() > MAX_ACTION_HISTORY) {
            actionHistory.removeFirst();
        }
    }

    String formatActionHistory() {
        if (actionHistory.isEmpty()) return "";
        int curScore = engine.getEvaluation().getCityScore();
        int curPop = engine.getCityPopulation();
        int curFunds = engine.getBudget().getTotalFunds();

        StringBuilder sb = new StringBuilder("[Recent Actions & Outcomes]\n");
        for (ActionRecord r : actionHistory) {
            sb.append("T").append(r.turn).append(": ").append(r.toolName)
              .append("(").append(r.inputSummary).append(")")
              .append(" -> score ").append(curScore - r.scoreBefore >= 0 ? "+" : "")
              .append(curScore - r.scoreBefore)
              .append(", pop ").append(curPop - r.popBefore >= 0 ? "+" : "")
              .append(curPop - r.popBefore)
              .append(", funds ").append(curFunds - r.fundsBefore >= 0 ? "+" : "")
              .append(curFunds - r.fundsBefore)
              .append("\n");
        }
        sb.append("[End Actions]");
        return sb.toString();
    }

    public void setOnObjectivesChanged(Consumer<String> listener) {
        this.onObjectivesChanged = listener;
    }

    private void fireObjectivesChanged() {
        emit(onObjectivesChanged, null);
    }

    public void queueUserMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            pendingUserMessages.add(message.trim());
        }
    }

    private void emit(Consumer<String> handler, String message) {
        if (handler != null) {
            handler.accept(message);
        }
    }
}
