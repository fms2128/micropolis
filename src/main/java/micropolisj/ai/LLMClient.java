package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Multi-provider LLM client supporting both Anthropic (Claude) and
 * OpenAI (ChatGPT) APIs. Normalizes responses to a common format
 * matching Anthropic's content-block structure so the rest of the
 * codebase can stay provider-agnostic.
 */
public class LLMClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;

    private final HttpClient httpClient;
    private String apiKey;
    private String model = "claude-sonnet-4-20250514";
    private LLMProvider provider = LLMProvider.ANTHROPIC;

    public LLMClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public void setProvider(LLMProvider provider) {
        this.provider = provider;
    }

    public LLMProvider getProvider() {
        return provider;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Send a request to the configured LLM provider.
     * Returns a normalized response in Anthropic's format regardless of provider.
     */
    public JsonObject sendRequest(String systemPrompt, JsonArray messages, JsonArray tools) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("API key not configured");
        }
        switch (provider) {
            case OPENAI:
                return sendOpenAIRequest(systemPrompt, messages, tools);
            case ANTHROPIC:
            default:
                return sendAnthropicRequest(systemPrompt, messages, tools);
        }
    }

    private JsonObject sendAnthropicRequest(String systemPrompt, JsonArray messages, JsonArray tools) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("system", systemPrompt);
        body.add("messages", messages);
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(LLMProvider.ANTHROPIC.apiUrl))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private boolean useMaxCompletionTokens() {
        return model.startsWith("o1") || model.startsWith("o3") || model.startsWith("gpt-5");
    }

    private JsonObject sendOpenAIRequest(String systemPrompt, JsonArray messages, JsonArray tools) throws Exception {
        JsonArray oaiMessages = convertMessagesToOpenAI(systemPrompt, messages);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        if (useMaxCompletionTokens()) {
            body.addProperty("max_completion_tokens", MAX_TOKENS);
        } else {
            body.addProperty("max_tokens", MAX_TOKENS);
        }
        body.add("messages", oaiMessages);
        if (tools != null && tools.size() > 0) {
            body.add("tools", convertToolsToOpenAI(tools));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(LLMProvider.OPENAI.apiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        JsonObject oaiResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        return normalizeOpenAIResponse(oaiResponse);
    }

    /**
     * Convert Anthropic-format messages to OpenAI format.
     * Anthropic: system is top-level, content is array of blocks.
     * OpenAI: system is a message, content is string or null, tool_calls separate.
     */
    private JsonArray convertMessagesToOpenAI(String systemPrompt, JsonArray anthropicMessages) {
        JsonArray oai = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        oai.add(sysMsg);

        for (JsonElement el : anthropicMessages) {
            JsonObject msg = el.getAsJsonObject();
            String role = msg.get("role").getAsString();
            JsonElement content = msg.get("content");

            if (content.isJsonPrimitive()) {
                JsonObject oaiMsg = new JsonObject();
                oaiMsg.addProperty("role", role);
                oaiMsg.addProperty("content", content.getAsString());
                oai.add(oaiMsg);
                continue;
            }

            if (!content.isJsonArray()) continue;
            JsonArray blocks = content.getAsJsonArray();

            if ("assistant".equals(role)) {
                JsonObject oaiMsg = new JsonObject();
                oaiMsg.addProperty("role", "assistant");
                StringBuilder text = new StringBuilder();
                JsonArray toolCalls = new JsonArray();

                for (JsonElement b : blocks) {
                    if (!b.isJsonObject()) continue;
                    JsonObject block = b.getAsJsonObject();
                    String type = block.get("type").getAsString();
                    if ("text".equals(type)) {
                        text.append(block.get("text").getAsString());
                    } else if ("tool_use".equals(type)) {
                        JsonObject tc = new JsonObject();
                        tc.addProperty("id", block.get("id").getAsString());
                        tc.addProperty("type", "function");
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", block.get("name").getAsString());
                        fn.addProperty("arguments", block.getAsJsonObject("input").toString());
                        tc.add("function", fn);
                        toolCalls.add(tc);
                    }
                }

                oaiMsg.addProperty("content", text.length() > 0 ? text.toString() : "");
                if (toolCalls.size() > 0) oaiMsg.add("tool_calls", toolCalls);
                oai.add(oaiMsg);

            } else if ("user".equals(role)) {
                boolean hasToolResults = false;
                for (JsonElement b : blocks) {
                    if (b.isJsonObject() && "tool_result".equals(b.getAsJsonObject().get("type").getAsString())) {
                        hasToolResults = true;
                        break;
                    }
                }

                if (hasToolResults) {
                    for (JsonElement b : blocks) {
                        if (!b.isJsonObject()) continue;
                        JsonObject block = b.getAsJsonObject();
                        if ("tool_result".equals(block.get("type").getAsString())) {
                            JsonObject toolMsg = new JsonObject();
                            toolMsg.addProperty("role", "tool");
                            toolMsg.addProperty("tool_call_id", block.get("tool_use_id").getAsString());
                            JsonElement contentEl = block.get("content");
                            if (contentEl.isJsonArray()) {
                                StringBuilder textContent = new StringBuilder();
                                for (JsonElement cb : contentEl.getAsJsonArray()) {
                                    if (cb.isJsonObject() && "text".equals(
                                            cb.getAsJsonObject().get("type").getAsString())) {
                                        textContent.append(cb.getAsJsonObject().get("text").getAsString());
                                    }
                                }
                                toolMsg.addProperty("content", textContent.toString());
                            } else {
                                toolMsg.addProperty("content", contentEl.getAsString());
                            }
                            oai.add(toolMsg);
                        }
                    }
                } else {
                    StringBuilder text = new StringBuilder();
                    for (JsonElement b : blocks) {
                        if (b.isJsonObject()) {
                            JsonObject block = b.getAsJsonObject();
                            if ("text".equals(block.get("type").getAsString())) {
                                text.append(block.get("text").getAsString());
                            }
                        }
                    }
                    JsonObject oaiMsg = new JsonObject();
                    oaiMsg.addProperty("role", "user");
                    oaiMsg.addProperty("content", text.toString());
                    oai.add(oaiMsg);
                }
            }
        }
        return oai;
    }

    /**
     * Convert Anthropic tool definitions to OpenAI format.
     */
    private JsonArray convertToolsToOpenAI(JsonArray anthropicTools) {
        JsonArray oai = new JsonArray();
        for (JsonElement el : anthropicTools) {
            JsonObject tool = el.getAsJsonObject();
            JsonObject oaiTool = new JsonObject();
            oaiTool.addProperty("type", "function");
            JsonObject fn = new JsonObject();
            fn.addProperty("name", tool.get("name").getAsString());
            fn.addProperty("description", tool.get("description").getAsString());
            fn.add("parameters", tool.getAsJsonObject("input_schema"));
            oaiTool.add("function", fn);
            oai.add(oaiTool);
        }
        return oai;
    }

    /**
     * Normalize an OpenAI response to Anthropic's content-block format.
     */
    private JsonObject normalizeOpenAIResponse(JsonObject oaiResponse) {
        JsonObject normalized = new JsonObject();
        JsonArray content = new JsonArray();

        JsonObject choice = oaiResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
            ? choice.get("finish_reason").getAsString() : "stop";

        if (message.has("content") && !message.get("content").isJsonNull()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", message.get("content").getAsString());
            content.add(textBlock);
        }

        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement tc : message.getAsJsonArray("tool_calls")) {
                JsonObject toolCall = tc.getAsJsonObject();
                JsonObject fn = toolCall.getAsJsonObject("function");
                JsonObject toolUseBlock = new JsonObject();
                toolUseBlock.addProperty("type", "tool_use");
                toolUseBlock.addProperty("id", toolCall.get("id").getAsString());
                toolUseBlock.addProperty("name", fn.get("name").getAsString());
                toolUseBlock.add("input", JsonParser.parseString(fn.get("arguments").getAsString()).getAsJsonObject());
                content.add(toolUseBlock);
            }
        }

        normalized.add("content", content);

        String stopReason;
        if ("tool_calls".equals(finishReason)) stopReason = "tool_use";
        else if ("stop".equals(finishReason)) stopReason = "end_turn";
        else if ("length".equals(finishReason)) stopReason = "max_tokens";
        else stopReason = finishReason;
        normalized.addProperty("stop_reason", stopReason);

        return normalized;
    }

    public CompletableFuture<JsonObject> sendRequestAsync(String systemPrompt, JsonArray messages, JsonArray tools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendRequest(systemPrompt, messages, tools);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static boolean hasToolUse(JsonObject response) {
        if (!response.has("content")) return false;
        for (JsonElement el : response.getAsJsonArray("content")) {
            if (el.isJsonObject() && "tool_use".equals(el.getAsJsonObject().get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    public static String extractText(JsonObject response) {
        StringBuilder sb = new StringBuilder();
        if (!response.has("content")) return "";
        for (JsonElement el : response.getAsJsonArray("content")) {
            if (el.isJsonObject()) {
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    sb.append(block.get("text").getAsString());
                }
            }
        }
        return sb.toString();
    }
}
