package micropolisj.gui;

import micropolisj.ai.AIAssistant;
import micropolisj.ai.ActivityLogger;
import micropolisj.ai.LLMProvider;
import micropolisj.engine.Micropolis;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * GUI panel for the AI assistant, embedded in the main game window.
 * Contains two tabs: Chat (AI reasoning/actions) and Activity Log (full event bus).
 */
public class AIAssistantPanel extends JPanel {

    private final AIAssistant assistant;
    private final JTextArea chatLog;
    private final JTextField apiKeyField;
    private final JToggleButton enableButton;
    private final JButton askButton;
    private final JTextField userInput;
    private final JComboBox<LLMProvider> providerSelector;
    private final JComboBox<String> modelSelector;
    private final JLabel statusLabel;

    private final JTextPane activityLogPane;
    private final JComboBox<String> activityFilter;
    private final JLabel activityCountLabel;
    private Timer activityRefreshTimer;
    private int lastLogLineCount = 0;

    private Timer nextTurnTimer;
    private boolean autoPlayEnabled = false;

    private static final Color BG_DARK = new Color(30, 30, 30);
    private static final Color FG_DEFAULT = new Color(200, 200, 200);
    private static final Color COLOR_TURN = new Color(100, 180, 255);
    private static final Color COLOR_TOOL_OK = new Color(130, 200, 130);
    private static final Color COLOR_TOOL_FAIL = new Color(230, 100, 100);
    private static final Color COLOR_EVENT_DISASTER = new Color(255, 80, 80);
    private static final Color COLOR_EVENT_CRITICAL = new Color(255, 140, 60);
    private static final Color COLOR_EVENT_MILESTONE = new Color(255, 220, 80);
    private static final Color COLOR_EVENT_DEMAND = new Color(160, 180, 255);
    private static final Color COLOR_EVENT_FINANCIAL = new Color(200, 160, 255);
    private static final Color COLOR_SNAPSHOT = new Color(130, 160, 160);
    private static final Color COLOR_USER = new Color(80, 220, 180);
    private static final Color COLOR_AGENT = new Color(180, 180, 180);
    private static final Color COLOR_REWARD_POS = new Color(100, 220, 100);
    private static final Color COLOR_REWARD_NEG = new Color(220, 100, 100);

    public AIAssistantPanel(AIAssistant assistant) {
        this.assistant = assistant;
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "AI Assistant",
            TitledBorder.CENTER, TitledBorder.TOP));
        setPreferredSize(new Dimension(320, 0));

        chatLog = new JTextArea();

        // ── Config panel (top) ──
        JPanel configPanel = new JPanel();
        configPanel.setLayout(new BoxLayout(configPanel, BoxLayout.PAGE_AXIS));
        configPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel apiKeyPanel = new JPanel(new BorderLayout(4, 0));
        apiKeyPanel.add(new JLabel("API Key:"), BorderLayout.LINE_START);
        apiKeyField = new JPasswordField(20);
        apiKeyField.setToolTipText("Enter your API key (Anthropic or OpenAI)");
        apiKeyPanel.add(apiKeyField, BorderLayout.CENTER);
        apiKeyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        configPanel.add(apiKeyPanel);
        configPanel.add(Box.createVerticalStrut(4));

        JPanel providerPanel = new JPanel(new BorderLayout(4, 0));
        providerPanel.add(new JLabel("Provider:"), BorderLayout.LINE_START);
        providerSelector = new JComboBox<>(LLMProvider.values());
        providerSelector.addActionListener(e -> onProviderChanged());
        providerPanel.add(providerSelector, BorderLayout.CENTER);
        providerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        configPanel.add(providerPanel);
        configPanel.add(Box.createVerticalStrut(4));

        JPanel modelPanel = new JPanel(new BorderLayout(4, 0));
        modelPanel.add(new JLabel("Model:"), BorderLayout.LINE_START);
        modelSelector = new JComboBox<>(LLMProvider.ANTHROPIC.getModels());
        modelSelector.addActionListener(e -> {
            String selected = (String) modelSelector.getSelectedItem();
            if (selected != null) assistant.getClient().setModel(selected);
        });
        modelPanel.add(modelSelector, BorderLayout.CENTER);
        modelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        configPanel.add(modelPanel);
        configPanel.add(Box.createVerticalStrut(4));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        enableButton = new JToggleButton("Auto-Play OFF");
        enableButton.addActionListener(this::onToggleAutoPlay);
        buttonPanel.add(enableButton);
        askButton = new JButton("Ask AI Now");
        askButton.addActionListener(this::onAskNow);
        buttonPanel.add(askButton);
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> { chatLog.setText(""); assistant.clearHistory(); lastLogLineCount = 0; });
        buttonPanel.add(clearButton);
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        configPanel.add(buttonPanel);
        configPanel.add(Box.createVerticalStrut(4));

        statusLabel = new JLabel("Status: Idle");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        configPanel.add(statusLabel);

        add(configPanel, BorderLayout.PAGE_START);

        // ── Tabbed pane (center) ──
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.BOTTOM);
        tabbedPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        // Tab 1: Chat log
        chatLog.setEditable(false);
        chatLog.setLineWrap(true);
        chatLog.setWrapStyleWord(true);
        chatLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        chatLog.setBackground(BG_DARK);
        chatLog.setForeground(FG_DEFAULT);
        chatLog.setCaretColor(Color.WHITE);
        JScrollPane chatScroll = new JScrollPane(chatLog);
        chatScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        tabbedPane.addTab("Chat", chatScroll);

        // Tab 2: Activity Log
        JPanel activityPanel = new JPanel(new BorderLayout(2, 2));

        JPanel activityToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        activityToolbar.add(new JLabel("Filter:"));
        activityFilter = new JComboBox<>(new String[]{
            "All", "Turns", "Tools", "Events", "Snapshots", "Agent", "User"
        });
        activityFilter.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        activityFilter.addActionListener(e -> refreshActivityLog());
        activityToolbar.add(activityFilter);
        activityCountLabel = new JLabel("0 entries");
        activityCountLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        activityCountLabel.setForeground(Color.GRAY);
        activityToolbar.add(activityCountLabel);
        activityPanel.add(activityToolbar, BorderLayout.PAGE_START);

        activityLogPane = new JTextPane();
        activityLogPane.setEditable(false);
        activityLogPane.setBackground(BG_DARK);
        activityLogPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane activityScroll = new JScrollPane(activityLogPane);
        activityScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        activityPanel.add(activityScroll, BorderLayout.CENTER);

        tabbedPane.addTab("Activity", activityPanel);

        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 1) {
                refreshActivityLog();
            }
        });

        add(tabbedPane, BorderLayout.CENTER);

        // ── Input panel (bottom) ──
        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        userInput = new JTextField();
        userInput.setToolTipText("Type a message to the AI (optional)");
        userInput.addActionListener(this::onAskNow);
        inputPanel.add(userInput, BorderLayout.CENTER);
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(this::onAskNow);
        inputPanel.add(sendButton, BorderLayout.LINE_END);
        add(inputPanel, BorderLayout.PAGE_END);

        // ── Callbacks ──
        assistant.setOnThinking(msg -> SwingUtilities.invokeLater(() -> appendLog("AI", msg)));
        assistant.setOnAction(msg -> SwingUtilities.invokeLater(() -> appendLog(">>", msg)));
        assistant.setOnError(msg -> SwingUtilities.invokeLater(() -> appendLog("ERROR", msg)));

        // Auto-refresh activity log every 3 seconds when visible
        activityRefreshTimer = new Timer(3000, e -> {
            if (tabbedPane.getSelectedIndex() == 1) {
                refreshActivityLog();
            }
        });
        activityRefreshTimer.start();
    }

    public void setEngine(Micropolis engine) {
        assistant.setEngine(engine);
    }

    // ── Activity Log rendering ──────────────────────────────────────

    private void refreshActivityLog() {
        ActivityLogger logger = assistant.getActivityLogger();
        if (logger == null || logger.getLogPath() == null) return;

        Path logPath = logger.getLogPath();
        if (!Files.exists(logPath)) {
            setActivityText("No activity log yet. Start a game and enable auto-play.", FG_DEFAULT);
            activityCountLabel.setText("0 entries");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(logPath);
            if (lines.size() == lastLogLineCount) return;
            lastLogLineCount = lines.size();

            String filter = (String) activityFilter.getSelectedItem();
            StyledDocument doc = new DefaultStyledDocument();
            int shown = 0;

            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                    String type = entry.has("type") ? entry.get("type").getAsString() : "unknown";

                    if (!matchesFilter(type, filter, entry)) continue;

                    String formatted = formatEntry(entry, type);
                    Color color = getColorForEntry(type, entry);
                    appendToDoc(doc, formatted + "\n", color);
                    shown++;
                } catch (Exception ignored) {
                    // skip malformed lines
                }
            }

            activityLogPane.setStyledDocument(doc);
            activityCountLabel.setText(shown + " / " + lines.size() + " entries");

            SwingUtilities.invokeLater(() ->
                activityLogPane.setCaretPosition(doc.getLength()));
        } catch (IOException e) {
            setActivityText("Error reading log: " + e.getMessage(), COLOR_TOOL_FAIL);
        }
    }

    private boolean matchesFilter(String type, String filter, JsonObject entry) {
        if (filter == null || "All".equals(filter)) return true;
        switch (filter) {
            case "Turns":
                return "turn_start".equals(type) || "turn_end".equals(type);
            case "Tools":
                return "tool_call".equals(type);
            case "Events":
                return "event".equals(type);
            case "Snapshots":
                return "snapshot".equals(type);
            case "Agent":
                return "agent_thinking".equals(type);
            case "User":
                return "event".equals(type) && entry.has("category")
                    && "USER_MESSAGE".equals(entry.get("category").getAsString());
            default:
                return true;
        }
    }

    private String formatEntry(JsonObject entry, String type) {
        String ts = "";
        if (entry.has("timestamp")) {
            String raw = entry.get("timestamp").getAsString();
            int tIdx = raw.indexOf('T');
            if (tIdx >= 0 && raw.length() > tIdx + 8) {
                ts = raw.substring(tIdx + 1, tIdx + 9);
            }
        }
        int turn = entry.has("turn") ? entry.get("turn").getAsInt() : -1;
        String prefix = ts.isEmpty() ? "" : "[" + ts + "] ";
        if (turn > 0) prefix += "T" + turn + " ";

        switch (type) {
            case "turn_start": {
                JsonObject state = entry.has("state") ? entry.getAsJsonObject("state") : null;
                if (state != null) {
                    return prefix + "── TURN START ── pop:" + g(state, "population")
                        + " funds:$" + g(state, "funds")
                        + " score:" + g(state, "score")
                        + " R:" + g(state, "res_demand") + " C:" + g(state, "com_demand")
                        + " I:" + g(state, "ind_demand");
                }
                return prefix + "── TURN START ──";
            }
            case "turn_end": {
                StringBuilder sb = new StringBuilder(prefix + "── TURN END ── ");
                sb.append("tools:").append(g(entry, "tool_calls_count"));
                sb.append(" (ok:").append(g(entry, "tools_succeeded"));
                sb.append(" fail:").append(g(entry, "tools_failed")).append(")");
                if (entry.has("reward")) {
                    JsonObject r = entry.getAsJsonObject("reward");
                    sb.append(" reward:").append(g(r, "instant"));
                    sb.append(" (score:").append(g(r, "score_component"));
                    sb.append(" pop:").append(g(r, "pop_component"));
                    sb.append(" funds:").append(g(r, "funds_component")).append(")");
                    if (r.has("trend")) sb.append(" trend:").append(g(r, "trend"));
                }
                if (entry.has("state_after")) {
                    JsonObject s = entry.getAsJsonObject("state_after");
                    sb.append("\n       ").append("pop:").append(g(s, "population"))
                        .append(" funds:$").append(g(s, "funds"))
                        .append(" score:").append(g(s, "score"));
                }
                if (entry.has("objectives")) {
                    JsonArray objs = entry.getAsJsonArray("objectives");
                    if (objs.size() > 0) {
                        sb.append("\n       objectives: ");
                        for (int i = 0; i < objs.size(); i++) {
                            if (i > 0) sb.append(" | ");
                            sb.append(objs.get(i).getAsString());
                        }
                    }
                }
                return sb.toString();
            }
            case "tool_call": {
                String tool = g(entry, "tool");
                boolean ok = entry.has("success") && entry.get("success").getAsBoolean();
                String status = ok ? "OK" : "FAIL";
                String fundsDelta = g(entry, "funds_delta");
                StringBuilder sb = new StringBuilder(prefix);
                sb.append(ok ? "+" : "x").append(" ").append(tool);
                if (entry.has("input")) {
                    sb.append(" ").append(summarizeToolInput(tool, entry.getAsJsonObject("input")));
                }
                sb.append(" [").append(status).append("]");
                if (!"0".equals(fundsDelta)) sb.append(" $").append(fundsDelta);
                String dur = g(entry, "duration_ms");
                if (!dur.isEmpty()) sb.append(" ").append(dur).append("ms");
                return sb.toString();
            }
            case "event": {
                String category = entry.has("category") ? entry.get("category").getAsString() : "?";
                String msg = entry.has("message") ? entry.get("message").getAsString() : "";
                return prefix + "[" + category + "] " + msg;
            }
            case "snapshot": {
                String snapType = entry.has("snapshot_type") ? entry.get("snapshot_type").getAsString() : "?";
                return prefix + formatSnapshot(snapType, entry.has("data") ? entry.getAsJsonObject("data") : null);
            }
            case "agent_thinking": {
                String text = entry.has("text") ? entry.get("text").getAsString() : "";
                if (text.length() > 200) text = text.substring(0, 200) + "...";
                return prefix + "AGENT: " + text;
            }
            default:
                return prefix + type + ": " + entry.toString().substring(0, Math.min(200, entry.toString().length()));
        }
    }

    private String formatSnapshot(String snapType, JsonObject data) {
        if (data == null) return snapType;
        switch (snapType) {
            case "CENSUS":
                return "CENSUS pop:" + g(data, "population")
                    + " R:" + g(data, "res_pop") + " C:" + g(data, "com_pop")
                    + " I:" + g(data, "ind_pop")
                    + " zones(R:" + g(data, "res_zones") + " C:" + g(data, "com_zones")
                    + " I:" + g(data, "ind_zones") + ")"
                    + " funds:$" + g(data, "funds")
                    + " " + g(data, "game_date");
            case "DEMAND_CHANGE":
                return "DEMAND R:" + g(data, "res_valve") + "(" + signed(data, "res_delta") + ")"
                    + " C:" + g(data, "com_valve") + "(" + signed(data, "com_delta") + ")"
                    + " I:" + g(data, "ind_valve") + "(" + signed(data, "ind_delta") + ")"
                    + caps(data);
            case "EVALUATION":
                return "EVAL score:" + g(data, "score")
                    + " delta:" + signed(data, "delta_score")
                    + " approval:" + g(data, "approval") + "%"
                    + " crime:" + g(data, "crime_avg")
                    + " pollution:" + g(data, "pollution_avg")
                    + " traffic:" + g(data, "traffic_avg");
            case "FUNDS_CHANGE":
                return "FUNDS $" + g(data, "funds")
                    + (data.has("delta") ? " (" + signed(data, "delta") + ")" : "");
            case "OPTIONS_CHANGE":
                return "OPTIONS speed:" + g(data, "speed") + " tax:" + g(data, "tax_rate") + "%";
            case "OBJECTIVES_SET": {
                StringBuilder sb = new StringBuilder("OBJECTIVES SET (");
                sb.append(g(data, "count")).append("): ");
                if (data.has("objectives")) {
                    JsonArray objs = data.getAsJsonArray("objectives");
                    for (int i = 0; i < objs.size(); i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(objs.get(i).getAsString());
                    }
                }
                return sb.toString();
            }
            case "OBJECTIVE_COMPLETED":
                return "OBJECTIVE DONE: " + g(data, "completed_objective")
                    + " (remaining:" + g(data, "remaining_active")
                    + " total_done:" + g(data, "total_completed") + ")";
            default:
                return snapType + " " + data.toString();
        }
    }

    private String summarizeToolInput(String tool, JsonObject input) {
        try {
            switch (tool) {
                case "place_zone":
                    return g(input, "type") + " @" + g(input, "x") + "," + g(input, "y");
                case "place_building":
                    return g(input, "type") + " @" + g(input, "x") + "," + g(input, "y");
                case "build_road": case "build_rail": case "build_power_line":
                    return g(input, "x1") + "," + g(input, "y1") + "->" + g(input, "x2") + "," + g(input, "y2");
                case "plan_city_block":
                    return g(input, "zone_type") + " " + g(input, "cols") + "x"
                        + (input.has("rows") ? g(input, "rows") : "2")
                        + " @" + g(input, "x") + "," + g(input, "y");
                case "set_tax_rate":
                    return g(input, "rate") + "%";
                case "set_budget":
                    return "road:" + g(input, "road_pct") + " police:" + g(input, "police_pct") + " fire:" + g(input, "fire_pct");
                case "set_speed":
                    return g(input, "speed");
                case "end_turn":
                    return g(input, "action");
                case "set_objectives":
                    return g(input, "objectives");
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private Color getColorForEntry(String type, JsonObject entry) {
        switch (type) {
            case "turn_start":
            case "turn_end":
                return COLOR_TURN;
            case "tool_call":
                return (entry.has("success") && entry.get("success").getAsBoolean())
                    ? COLOR_TOOL_OK : COLOR_TOOL_FAIL;
            case "event": {
                String cat = entry.has("category") ? entry.get("category").getAsString() : "";
                switch (cat) {
                    case "DISASTER": return COLOR_EVENT_DISASTER;
                    case "CRITICAL": return COLOR_EVENT_CRITICAL;
                    case "MILESTONE": return COLOR_EVENT_MILESTONE;
                    case "DEMAND": return COLOR_EVENT_DEMAND;
                    case "FINANCIAL": return COLOR_EVENT_FINANCIAL;
                    case "USER_MESSAGE": return COLOR_USER;
                    default: return FG_DEFAULT;
                }
            }
            case "snapshot": {
                String st = entry.has("snapshot_type") ? entry.get("snapshot_type").getAsString() : "";
                if ("OBJECTIVES_SET".equals(st) || "OBJECTIVE_COMPLETED".equals(st))
                    return COLOR_EVENT_MILESTONE;
                return COLOR_SNAPSHOT;
            }
            case "agent_thinking":
                return COLOR_AGENT;
            default:
                return FG_DEFAULT;
        }
    }

    private void appendToDoc(StyledDocument doc, String text, Color color) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setFontFamily(attrs, Font.MONOSPACED);
        StyleConstants.setFontSize(attrs, 11);
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {}
    }

    private void setActivityText(String text, Color color) {
        StyledDocument doc = new DefaultStyledDocument();
        appendToDoc(doc, text, color);
        activityLogPane.setStyledDocument(doc);
    }

    private static String g(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private static String signed(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "0";
        int v = obj.get(key).getAsInt();
        return v >= 0 ? "+" + v : String.valueOf(v);
    }

    private static String caps(JsonObject data) {
        StringBuilder sb = new StringBuilder();
        if (data.has("res_capped") && data.get("res_capped").getAsBoolean()) sb.append(" RES_CAPPED!");
        if (data.has("com_capped") && data.get("com_capped").getAsBoolean()) sb.append(" COM_CAPPED!");
        if (data.has("ind_capped") && data.get("ind_capped").getAsBoolean()) sb.append(" IND_CAPPED!");
        return sb.toString();
    }

    // ── Chat / control callbacks ────────────────────────────────────

    private void onToggleAutoPlay(ActionEvent e) {
        applyApiKey();
        autoPlayEnabled = enableButton.isSelected();
        assistant.setAutoPlayActive(autoPlayEnabled);
        enableButton.setText(autoPlayEnabled ? "Auto-Play ON" : "Auto-Play OFF");

        if (autoPlayEnabled) {
            statusLabel.setText("Status: Auto-play active");
            scheduleNextAutoTurn(0);
        } else {
            cancelScheduledTurn();
            statusLabel.setText("Status: Idle");
        }
    }

    private void onAskNow(ActionEvent e) {
        applyApiKey();
        if (!assistant.isConfigured()) {
            appendLog("ERROR", "Please enter your API key first.");
            return;
        }
        String userMsg = userInput.getText().trim();
        userInput.setText("");

        if (userMsg.isEmpty()) {
            if (!assistant.isRunning()) {
                statusLabel.setText("Status: Thinking...");
                askButton.setEnabled(false);
                assistant.runTurn(null).thenRun(() ->
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Status: " + (autoPlayEnabled ? "Auto-play active" : "Idle"));
                        askButton.setEnabled(true);
                    })
                );
            }
            return;
        }

        appendLog("You", userMsg);

        if (assistant.isRunning()) {
            assistant.queueUserMessage(userMsg);
            statusLabel.setText("Status: Message queued (agent busy)");
        } else {
            statusLabel.setText("Status: Thinking...");
            askButton.setEnabled(false);
            assistant.runTurn(userMsg).thenRun(() ->
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: " + (autoPlayEnabled ? "Auto-play active" : "Idle"));
                    askButton.setEnabled(true);
                })
            );
        }
    }

    private void onProviderChanged() {
        LLMProvider selected = (LLMProvider) providerSelector.getSelectedItem();
        if (selected == null) return;
        assistant.getClient().setProvider(selected);
        modelSelector.removeAllItems();
        for (String m : selected.getModels()) {
            modelSelector.addItem(m);
        }
        assistant.getClient().setModel(selected.getModels()[0]);
        assistant.clearHistory();
    }

    private void applyApiKey() {
        String key = apiKeyField.getText().trim();
        if (!key.isEmpty()) {
            assistant.getClient().setApiKey(key);
        }
    }

    private void scheduleNextAutoTurn(int delayMs) {
        cancelScheduledTurn();
        if (!autoPlayEnabled || !assistant.isConfigured()) return;

        int effectiveDelay = Math.max(delayMs, 100);
        nextTurnTimer = new Timer(effectiveDelay, e -> {
            if (!autoPlayEnabled || !assistant.isConfigured()) return;

            if (assistant.isRunning()) {
                scheduleNextAutoTurn(2000);
                return;
            }

            statusLabel.setText("Status: Auto-turn...");
            assistant.runTurn().whenComplete((result, error) ->
                SwingUtilities.invokeLater(() -> {
                    if (!autoPlayEnabled) {
                        statusLabel.setText("Status: Idle");
                        return;
                    }
                    int nextDelay = assistant.getNextTurnDelayMs();
                    if (nextDelay > 3000 && assistant.getListener().hasCriticalEvent()) {
                        nextDelay = 3000;
                    }
                    statusLabel.setText(nextDelay == 0
                        ? "Status: Continuing immediately..."
                        : "Status: Waiting " + (nextDelay / 1000) + "s for simulation...");
                    scheduleNextAutoTurn(nextDelay);
                }));
        });
        nextTurnTimer.setRepeats(false);
        nextTurnTimer.start();
    }

    private void cancelScheduledTurn() {
        if (nextTurnTimer != null) {
            nextTurnTimer.stop();
            nextTurnTimer = null;
        }
    }

    private void appendLog(String prefix, String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        chatLog.append("[" + time + "] " + prefix + ": " + message + "\n");
        chatLog.setCaretPosition(chatLog.getDocument().getLength());
    }
}
