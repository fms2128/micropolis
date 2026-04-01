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
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private final AnthropicClient client;

    private final List<JsonObject> conversationHistory = new ArrayList<>();
    private Consumer<String> onThinking;
    private Consumer<String> onAction;
    private Consumer<String> onError;

    private volatile boolean running = false;
    private int turnCount = 0;

    public AIAssistant() {
        this.client = new AnthropicClient();
    }

    public void setEngine(Micropolis engine) {
        if (this.engine != null && this.listener != null) {
            this.engine.removeListener(this.listener);
        }
        this.engine = engine;
        this.observer = new GameStateObserver(engine);
        this.toolHandler = new AIToolHandler(engine, observer);
        this.listener = new AIGameListener();
        engine.addListener(this.listener);
    }

    public AnthropicClient getClient() {
        return client;
    }

    public void setOnThinking(Consumer<String> onThinking) {
        this.onThinking = onThinking;
    }

    public void setOnAction(Consumer<String> onAction) {
        this.onAction = onAction;
    }

    public void setOnError(Consumer<String> onError) {
        this.onError = onError;
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
        String stateJson = observer.getFullStateText();
        String events = listener.drainMessages();
        listener.clearCriticalEvent();

        StringBuilder userContent = new StringBuilder();
        userContent.append("[Turn ").append(turnCount).append("] Current city state:\n");
        userContent.append(stateJson);
        if (!events.isEmpty()) {
            userContent.append("\n\nRecent events: ").append(events);
        }
        if (userMessage != null && !userMessage.isEmpty()) {
            userContent.append("\n\nUser request: ").append(userMessage);
        }
        userContent.append("\n\nAnalyze the city state and take appropriate actions to grow and improve the city.");

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

            String text = AnthropicClient.extractText(response);
            if (!text.isEmpty()) {
                emit(onThinking, text);
            }

            if (!"tool_use".equals(stopReason) || !AnthropicClient.hasToolUse(response)) {
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

                emit(onAction, "Result: " + result.toString());

                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", toolId);
                toolResult.addProperty("content", result.toString());
                toolResults.add(toolResult);
            }

            JsonObject toolResultMsg = new JsonObject();
            toolResultMsg.addProperty("role", "user");
            toolResultMsg.add("content", toolResults);
            messages.add(toolResultMsg);
            conversationHistory.add(toolResultMsg);

            toolRounds++;
        }

        trimHistory();
    }

    private void trimHistory() {
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.remove(0);
        }
        // Ensure history starts with a plain user message (not tool results).
        // Remove leading messages until we find a user message that contains
        // regular text content (not tool_result blocks), because OpenAI requires
        // tool role messages to follow an assistant message with tool_calls.
        while (!conversationHistory.isEmpty()) {
            JsonObject first = conversationHistory.get(0);
            String role = first.get("role").getAsString();
            if ("user".equals(role) && !isToolResultMessage(first)) {
                break;
            }
            conversationHistory.remove(0);
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

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(SystemPrompt.PROMPT);
        try {
            Path logPath = Paths.get("ai_learnings.log");
            if (Files.exists(logPath)) {
                String learnings = new String(Files.readAllBytes(logPath));
                if (!learnings.trim().isEmpty()) {
                    sb.append("\n\n## Your Past Learnings (from previous turns)\n");
                    sb.append("These are observations you recorded. Use them to make better decisions:\n");
                    sb.append(learnings);
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return sb.toString();
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
        turnCount = 0;
    }

    private void emit(Consumer<String> handler, String message) {
        if (handler != null) {
            handler.accept(message);
        }
    }
}
