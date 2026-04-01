package micropolisj.ai;

public enum LLMProvider {
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1/messages"),
    OPENAI("OpenAI (ChatGPT)", "https://api.openai.com/v1/chat/completions");

    public final String displayName;
    public final String apiUrl;

    LLMProvider(String displayName, String apiUrl) {
        this.displayName = displayName;
        this.apiUrl = apiUrl;
    }

    public String[] getModels() {
        switch (this) {
            case ANTHROPIC:
                return new String[]{
                    "claude-sonnet-4-20250514",
                    "claude-opus-4-20250514"
                };
            case OPENAI:
                return new String[]{
                    "gpt-4o",
                    "gpt-4o-mini",
                    "o3-mini",
                    "gpt-4.1"
                };
            default:
                return new String[]{};
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
