package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Meta-agent that runs every N turns to reflect on game progress and
 * optionally update the system prompt with strategic additions.
 * Uses a separate API call with its own context — cannot take game actions,
 * only observe and adjust the playing agent's instructions.
 */
public class ReflectiveAgent {

    private static final String REFLECTIVE_PROMPT_FILE = "ai_data/reflective_prompt.md";
    private static final int MAX_TOOL_ROUNDS = 4;

    private final LLMClient client;
    private Consumer<String> onThinking;

    public ReflectiveAgent(LLMClient client) {
        this.client = client;
    }

    public void setOnThinking(Consumer<String> onThinking) {
        this.onThinking = onThinking;
    }

    /**
     * Run a reflection cycle. Collects full game context and asks the LLM
     * to evaluate the current strategy and optionally update the system prompt.
     */
    public void runReflection(
            List<JsonObject> conversationHistory,
            List<AIAssistant.Objective> currentObjectives,
            List<AIAssistant.Objective> completedObjectives,
            LinkedList<Double> rewardHistory,
            GameStateObserver observer,
            int turnCount) {

        try {
            emit("Running strategic reflection (turn " + turnCount + ")...");
            executeReflection(conversationHistory, currentObjectives,
                completedObjectives, rewardHistory, observer, turnCount);
        } catch (Exception e) {
            emit("Reflection error: " + e.getMessage());
        }
    }

    private void executeReflection(
            List<JsonObject> conversationHistory,
            List<AIAssistant.Objective> currentObjectives,
            List<AIAssistant.Objective> completedObjectives,
            LinkedList<Double> rewardHistory,
            GameStateObserver observer,
            int turnCount) throws Exception {

        String systemPrompt = buildReflectionSystemPrompt();

        StringBuilder userContent = new StringBuilder();
        userContent.append("## Reflection at Turn ").append(turnCount).append("\n\n");

        userContent.append("### Current Game State\n");
        userContent.append(observer.getFullState().toString()).append("\n\n");

        userContent.append("### Evaluation\n");
        userContent.append(observer.getEvaluation().toString()).append("\n\n");

        userContent.append("### Reward History (last ").append(rewardHistory.size()).append(" turns)\n");
        userContent.append(rewardHistory.toString()).append("\n");
        if (rewardHistory.size() >= 3) {
            double avg = rewardHistory.stream().mapToDouble(d -> d).average().orElse(0);
            double recent = rewardHistory.stream().skip(Math.max(0, rewardHistory.size() - 3))
                .mapToDouble(d -> d).average().orElse(0);
            userContent.append("Average reward: ").append(String.format("%.2f", avg));
            userContent.append(", Recent 3-turn avg: ").append(String.format("%.2f", recent)).append("\n");
        }
        userContent.append("\n");

        userContent.append("### Current Objectives\n");
        if (currentObjectives.isEmpty()) {
            userContent.append("(none set)\n");
        } else {
            for (int i = 0; i < currentObjectives.size(); i++) {
                userContent.append(i + 1).append(". ").append(currentObjectives.get(i).getText()).append("\n");
            }
        }
        userContent.append("\n### Completed Objectives\n");
        for (AIAssistant.Objective obj : completedObjectives) {
            userContent.append("- [DONE] ").append(obj.getText()).append("\n");
        }
        userContent.append("\n");

        userContent.append("### Metrics History\n");
        userContent.append(observer.getHistoryData("all", "recent").toString()).append("\n\n");

        userContent.append("### Conversation Summary (last messages)\n");
        userContent.append(summarizeConversation(conversationHistory)).append("\n\n");

        String currentAdditions = loadCurrentAdditions();
        userContent.append("### Current System Prompt Additions\n");
        if (currentAdditions.isEmpty()) {
            userContent.append("(none — base prompt only)\n");
        } else {
            userContent.append(currentAdditions).append("\n");
        }
        userContent.append("\n");

        userContent.append("### Base System Prompt\n");
        userContent.append(SystemPrompt.PROMPT).append("\n");

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray userContentArr = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", userContent.toString());
        userContentArr.add(textBlock);
        userMsg.add("content", userContentArr);

        JsonArray messages = new JsonArray();
        messages.add(userMsg);

        JsonArray tools = getReflectionTools();

        int toolRounds = 0;
        while (toolRounds < MAX_TOOL_ROUNDS) {
            JsonObject response = client.sendRequest(systemPrompt, messages, tools);
            String stopReason = response.has("stop_reason")
                ? response.get("stop_reason").getAsString() : "end_turn";

            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.add("content", response.getAsJsonArray("content"));
            messages.add(assistantMsg);

            String text = LLMClient.extractText(response);
            if (!text.isEmpty()) {
                emit("[Reflection] " + text);
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

                JsonObject result = executeReflectionTool(toolName, input);
                emit("[Reflection Tool] " + toolName + " -> " + result.toString());

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

            toolRounds++;
        }
    }

    private String buildReflectionSystemPrompt() {
        return String.join("\n",
            "You are a STRATEGIC ADVISOR for a Micropolis (SimCity) AI agent.",
            "Your job is to review the agent's performance every 10 turns and improve its strategy.",
            "",
            "You receive:",
            "- The full game state (population, score, budget, infrastructure, metrics history)",
            "- The agent's reward history (positive = good decisions, negative = bad)",
            "- The agent's current and completed objectives",
            "- A summary of recent conversation/actions",
            "- The current system prompt (base + any additions you previously wrote)",
            "",
            "Your task:",
            "1. DIAGNOSE: What is working? What is failing? Look at reward trends, score changes, recurring problems.",
            "2. IDENTIFY PATTERNS: Is the agent repeating mistakes? Ignoring certain problems? Over-investing in one area?",
            "3. DECIDE: Should you update the system prompt additions?",
            "",
            "When to update the prompt:",
            "- The agent keeps making the same mistake (e.g., building without power, ignoring floods)",
            "- A strategy is clearly not working (reward_trend declining for 5+ turns)",
            "- You discover a better approach based on the game state",
            "- The agent's priorities are misaligned with the city's actual problems",
            "",
            "When NOT to update:",
            "- Things are generally working (positive reward trend)",
            "- It's too early to judge (first reflection at turn 10)",
            "- The current additions are already addressing the issue",
            "",
            "IMPORTANT:",
            "- Your additions are APPENDED to the base system prompt, not replacing it.",
            "- Keep additions focused and concise (max ~500 words).",
            "- Write additions as direct instructions: 'ALWAYS do X', 'NEVER do Y', 'When Z happens, do W'.",
            "- Include specific numbers/thresholds when possible.",
            "- Each reflection should build on previous additions, not duplicate them.",
            "- If the current additions are outdated or wrong, use replace mode to overwrite them."
        );
    }

    private JsonArray getReflectionTools() {
        JsonArray tools = new JsonArray();

        JsonObject updateTool = new JsonObject();
        updateTool.addProperty("name", "update_system_prompt");
        updateTool.addProperty("description",
            "Update the strategic additions to the playing agent's system prompt. "
            + "These additions are appended after the base prompt and before agent memory. "
            + "Use 'append' mode to add new rules, or 'replace' mode to rewrite all additions from scratch.");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject contentProp = new JsonObject();
        contentProp.addProperty("type", "string");
        contentProp.addProperty("description",
            "The strategic additions text. Write as direct instructions for the agent. "
            + "In 'replace' mode, this becomes the entire additions section. "
            + "In 'append' mode, this is appended to existing additions.");
        props.add("content", contentProp);
        JsonObject modeProp = new JsonObject();
        modeProp.addProperty("type", "string");
        modeProp.addProperty("description", "'append' to add to existing additions, 'replace' to overwrite them entirely");
        props.add("mode", modeProp);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("content");
        required.add("mode");
        schema.add("required", required);
        updateTool.add("input_schema", schema);
        tools.add(updateTool);

        JsonObject logTool = new JsonObject();
        logTool.addProperty("name", "log_reflection");
        logTool.addProperty("description",
            "Log a reflection observation without changing the system prompt. "
            + "Use this to record your analysis for the next reflection cycle.");
        JsonObject logSchema = new JsonObject();
        logSchema.addProperty("type", "object");
        JsonObject logProps = new JsonObject();
        JsonObject noteProp = new JsonObject();
        noteProp.addProperty("type", "string");
        noteProp.addProperty("description", "Your reflection observation or analysis");
        logProps.add("note", noteProp);
        logSchema.add("properties", logProps);
        JsonArray logRequired = new JsonArray();
        logRequired.add("note");
        logSchema.add("required", logRequired);
        logTool.add("input_schema", logSchema);
        tools.add(logTool);

        return tools;
    }

    private JsonObject executeReflectionTool(String toolName, JsonObject input) {
        JsonObject r = new JsonObject();
        switch (toolName) {
            case "update_system_prompt":
                return executeUpdateSystemPrompt(input);
            case "log_reflection":
                return executeLogReflection(input);
            default:
                r.addProperty("error", "Unknown reflection tool: " + toolName);
                return r;
        }
    }

    private JsonObject executeUpdateSystemPrompt(JsonObject input) {
        String content = input.get("content").getAsString();
        String mode = input.get("mode").getAsString().toLowerCase();
        JsonObject r = new JsonObject();
        try {
            Path dir = Paths.get("ai_data");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path p = Paths.get(REFLECTIVE_PROMPT_FILE);

            if ("replace".equals(mode)) {
                Files.write(p, content.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                r.addProperty("success", true);
                r.addProperty("message", "System prompt additions replaced entirely.");
            } else {
                String existing = "";
                if (Files.exists(p)) {
                    existing = new String(Files.readAllBytes(p));
                }
                String updated = existing.isEmpty() ? content : existing + "\n\n" + content;
                Files.write(p, updated.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                r.addProperty("success", true);
                r.addProperty("message", "Appended to system prompt additions.");
            }
            r.addProperty("total_length", new String(Files.readAllBytes(p)).length());
        } catch (IOException e) {
            r.addProperty("success", false);
            r.addProperty("error", e.getMessage());
        }
        return r;
    }

    private JsonObject executeLogReflection(JsonObject input) {
        String note = input.get("note").getAsString();
        JsonObject r = new JsonObject();
        try {
            Path dir = Paths.get("ai_data");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path p = Paths.get("ai_data/reflection_log.md");
            String entry = "---\n" + note + "\n";
            Files.write(p, entry.getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            r.addProperty("success", true);
            r.addProperty("message", "Reflection logged.");
        } catch (IOException e) {
            r.addProperty("success", false);
            r.addProperty("error", e.getMessage());
        }
        return r;
    }

    private String loadCurrentAdditions() {
        try {
            Path p = Paths.get(REFLECTIVE_PROMPT_FILE);
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p));
            }
        } catch (IOException e) {
            // ignore
        }
        return "";
    }

    private String summarizeConversation(List<JsonObject> conversationHistory) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = Math.max(0, conversationHistory.size() - 10); i < conversationHistory.size(); i++) {
            JsonObject msg = conversationHistory.get(i);
            String role = msg.get("role").getAsString();
            JsonElement content = msg.get("content");

            if ("user".equals(role) && content.isJsonArray()) {
                boolean isToolResult = false;
                for (JsonElement el : content.getAsJsonArray()) {
                    if (el.isJsonObject() && "tool_result".equals(
                            el.getAsJsonObject().has("type")
                            ? el.getAsJsonObject().get("type").getAsString() : "")) {
                        isToolResult = true;
                        break;
                    }
                }
                if (isToolResult) continue;

                for (JsonElement el : content.getAsJsonArray()) {
                    if (el.isJsonObject() && "text".equals(
                            el.getAsJsonObject().get("type").getAsString())) {
                        String text = el.getAsJsonObject().get("text").getAsString();
                        if (text.length() > 500) text = text.substring(0, 500) + "...";
                        sb.append("[User/Turn] ").append(text).append("\n\n");
                        shown++;
                    }
                }
            } else if ("assistant".equals(role) && content.isJsonArray()) {
                for (JsonElement el : content.getAsJsonArray()) {
                    if (el.isJsonObject() && "text".equals(
                            el.getAsJsonObject().get("type").getAsString())) {
                        String text = el.getAsJsonObject().get("text").getAsString();
                        if (text.length() > 300) text = text.substring(0, 300) + "...";
                        sb.append("[Agent] ").append(text).append("\n\n");
                        shown++;
                    }
                }
            }
            if (shown >= 8) break;
        }
        return sb.length() > 0 ? sb.toString() : "(no conversation history)";
    }

    private void emit(String message) {
        if (onThinking != null) {
            onThinking.accept(message);
        }
    }
}
