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
    private final JSpinner intervalSpinner;
    private final JLabel statusLabel;

    private Timer autoTimer;
    private int simStepsSinceLastTurn = 0;
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

        JPanel intervalPanel = new JPanel(new BorderLayout(4, 0));
        intervalPanel.add(new JLabel("Auto-play interval (s):"), BorderLayout.LINE_START);
        intervalSpinner = new JSpinner(new SpinnerNumberModel(30, 5, 300, 5));
        intervalPanel.add(intervalSpinner, BorderLayout.CENTER);
        intervalPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        configPanel.add(intervalPanel);
        configPanel.add(Box.createVerticalStrut(4));

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
        enableButton.setText(autoPlayEnabled ? "Auto-Play ON" : "Auto-Play OFF");

        if (autoPlayEnabled) {
            startAutoTimer();
            statusLabel.setText("Status: Auto-play active");
        } else {
            stopAutoTimer();
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
        if (!userMsg.isEmpty()) {
            appendLog("You", userMsg);
        }
        statusLabel.setText("Status: Thinking...");
        askButton.setEnabled(false);

        assistant.runTurn(userMsg.isEmpty() ? null : userMsg).thenRun(() ->
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Status: " + (autoPlayEnabled ? "Auto-play active" : "Idle"));
                askButton.setEnabled(true);
            })
        );
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

    private void startAutoTimer() {
        stopAutoTimer();
        int intervalMs = ((Number) intervalSpinner.getValue()).intValue() * 1000;
        autoTimer = new Timer(intervalMs, e -> {
            if (autoPlayEnabled && !assistant.isRunning() && assistant.isConfigured()) {
                statusLabel.setText("Status: Auto-turn...");
                assistant.runTurn().thenRun(() ->
                    SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Status: Auto-play active")));
            }
        });
        autoTimer.setRepeats(true);
        autoTimer.start();
    }

    private void stopAutoTimer() {
        if (autoTimer != null) {
            autoTimer.stop();
            autoTimer = null;
        }
    }

    private void appendLog(String prefix, String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        chatLog.append("[" + time + "] " + prefix + ": " + message + "\n");
        chatLog.setCaretPosition(chatLog.getDocument().getLength());
    }
}
