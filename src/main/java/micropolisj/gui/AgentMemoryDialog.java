package micropolisj.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ResourceBundle;

public class AgentMemoryDialog extends JDialog {

	private static final ResourceBundle strings = MainWindow.strings;
	private static final Path STRATEGY_FILE = Paths.get("ai_data", "game_strategy_guide.md");

	private final JTextArea textArea;
	private final JLabel statusLabel;

	public AgentMemoryDialog(Frame owner) {
		super(owner, strings.getString("menu.windows.agent_memory"), false);
		setSize(700, 600);
		setLocationRelativeTo(owner);

		JPanel content = new JPanel(new BorderLayout(0, 4));
		content.setBorder(new EmptyBorder(8, 8, 8, 8));
		setContentPane(content);

		textArea = new JTextArea();
		textArea.setEditable(false);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		textArea.setBackground(new Color(245, 245, 245));

		JScrollPane scrollPane = new JScrollPane(textArea);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		content.add(scrollPane, BorderLayout.CENTER);

		JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
		statusLabel = new JLabel(" ");
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
		bottomPanel.add(statusLabel, BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		JButton refreshBtn = new JButton(strings.getString("agent_memory.refresh"));
		refreshBtn.addActionListener(e -> loadFile());
		buttonPanel.add(refreshBtn);

		JButton closeBtn = new JButton(strings.getString("agent_memory.close"));
		closeBtn.addActionListener(e -> setVisible(false));
		buttonPanel.add(closeBtn);

		bottomPanel.add(buttonPanel, BorderLayout.LINE_END);
		content.add(bottomPanel, BorderLayout.PAGE_END);

		loadFile();
	}

	private void loadFile() {
		try {
			if (Files.exists(STRATEGY_FILE)) {
				String text = new String(Files.readAllBytes(STRATEGY_FILE));
				textArea.setText(text);
				textArea.setCaretPosition(0);
				statusLabel.setText(STRATEGY_FILE.toAbsolutePath().toString());
			} else {
				textArea.setText(strings.getString("agent_memory.not_found"));
				statusLabel.setText(" ");
			}
		} catch (IOException ex) {
			textArea.setText(strings.getString("agent_memory.error") + "\n" + ex.getMessage());
			statusLabel.setText(" ");
		}
	}

	@Override
	public void setVisible(boolean visible) {
		if (visible) {
			loadFile();
		}
		super.setVisible(visible);
	}
}
