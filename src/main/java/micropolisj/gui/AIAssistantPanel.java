package micropolisj.gui;

import micropolisj.ai.AIAssistant;
import micropolisj.ai.LLMProvider;
import micropolisj.engine.Micropolis;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * GUI panel for the AI assistant, embedded in the main game window.
 * Provides API key input, enable/disable toggle, auto-play controls,
 * and a chat log showing AI reasoning and actions.
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

    private Timer nextTurnTimer;
    private boolean autoPlayEnabled = false;

    public AIAssistantPanel(AIAssistant assistant) {
        this.assistant = assistant;
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "AI Assistant",
            TitledBorder.CENTER, TitledBorder.TOP));
        setPreferredSize(new Dimension(320, 0));

        chatLog = new JTextArea();

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

        configPanel.add(Box.createVerticalStrut(2));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        enableButton = new JToggleButton("Auto-Play OFF");
        enableButton.addActionListener(this::onToggleAutoPlay);
        buttonPanel.add(enableButton);
        askButton = new JButton("Ask AI Now");
        askButton.addActionListener(this::onAskNow);
        buttonPanel.add(askButton);
        JButton clearButton = new JButton("Clear Log");
        clearButton.addActionListener(e -> { chatLog.setText(""); assistant.clearHistory(); });
        buttonPanel.add(clearButton);
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        configPanel.add(buttonPanel);
        configPanel.add(Box.createVerticalStrut(4));

        statusLabel = new JLabel("Status: Idle");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        configPanel.add(statusLabel);

        add(configPanel, BorderLayout.PAGE_START);

        chatLog.setEditable(false);
        chatLog.setLineWrap(true);
        chatLog.setWrapStyleWord(true);
        chatLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        chatLog.setBackground(new Color(30, 30, 30));
        chatLog.setForeground(new Color(200, 200, 200));
        chatLog.setCaretColor(Color.WHITE);
        JScrollPane scrollPane = new JScrollPane(chatLog);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);

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

        assistant.setOnThinking(msg -> SwingUtilities.invokeLater(() -> appendLog("AI", msg)));
        assistant.setOnAction(msg -> SwingUtilities.invokeLater(() -> appendLog(">>", msg)));
        assistant.setOnError(msg -> SwingUtilities.invokeLater(() -> appendLog("ERROR", msg)));
    }

    public void setEngine(Micropolis engine) {
        assistant.setEngine(engine);
    }

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
