package micropolisj.gui;

import micropolisj.ai.AIAssistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.ResourceBundle;

public class ObjectivesDialog extends JDialog {

	private static final ResourceBundle strings = MainWindow.strings;
	private static final Color ACTIVE_BG = new Color(255, 255, 235);
	private static final Color COMPLETED_BG = new Color(230, 250, 230);
	private static final Color COMPLETED_FG = new Color(80, 140, 80);
	private static final Color EMPTY_FG = new Color(140, 140, 140);

	private final AIAssistant assistant;
	private final JPanel listPanel;
	private final JLabel statusLabel;

	public ObjectivesDialog(Frame owner, AIAssistant assistant) {
		super(owner, strings.getString("menu.windows.objectives"), false);
		this.assistant = assistant;
		setSize(420, 400);
		setLocationRelativeTo(owner);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(new EmptyBorder(10, 10, 10, 10));
		setContentPane(content);

		JLabel title = new JLabel(strings.getString("objectives.title"));
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
		title.setHorizontalAlignment(SwingConstants.CENTER);
		content.add(title, BorderLayout.PAGE_START);

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.PAGE_AXIS));
		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		content.add(scrollPane, BorderLayout.CENTER);

		JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
		statusLabel = new JLabel(" ");
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
		bottomPanel.add(statusLabel, BorderLayout.CENTER);

		JButton closeBtn = new JButton(strings.getString("objectives.close"));
		closeBtn.addActionListener(e -> setVisible(false));
		bottomPanel.add(closeBtn, BorderLayout.LINE_END);
		content.add(bottomPanel, BorderLayout.PAGE_END);

		assistant.setOnObjectivesChanged(v ->
			SwingUtilities.invokeLater(this::refreshObjectives)
		);

		refreshObjectives();
	}

	private void refreshObjectives() {
		listPanel.removeAll();

		List<AIAssistant.Objective> active = assistant.getObjectives();
		List<AIAssistant.Objective> completed = assistant.getCompletedObjectives();

		if (active.isEmpty() && completed.isEmpty()) {
			JLabel emptyLabel = new JLabel(strings.getString("objectives.none"));
			emptyLabel.setForeground(EMPTY_FG);
			emptyLabel.setFont(emptyLabel.getFont().deriveFont(Font.ITALIC, 13f));
			emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
			emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			emptyLabel.setBorder(new EmptyBorder(40, 20, 40, 20));
			listPanel.add(emptyLabel);
		} else {
			if (!active.isEmpty()) {
				listPanel.add(makeSectionHeader(strings.getString("objectives.active")));
				for (int i = 0; i < active.size(); i++) {
					listPanel.add(makeObjectiveRow(i + 1, active.get(i).getText(), false));
				}
			}

			if (!completed.isEmpty()) {
				if (!active.isEmpty()) {
					listPanel.add(Box.createVerticalStrut(12));
				}
				listPanel.add(makeSectionHeader(strings.getString("objectives.completed")));
				for (int i = 0; i < completed.size(); i++) {
					listPanel.add(makeObjectiveRow(i + 1, completed.get(i).getText(), true));
				}
			}
		}

		listPanel.add(Box.createVerticalGlue());
		listPanel.revalidate();
		listPanel.repaint();

		statusLabel.setText(active.size() + " " + strings.getString("objectives.active_count")
			+ " / " + completed.size() + " " + strings.getString("objectives.completed_count"));
	}

	private JComponent makeSectionHeader(String text) {
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
		label.setBorder(new EmptyBorder(4, 4, 4, 4));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.add(label, BorderLayout.LINE_START);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		return wrapper;
	}

	private JComponent makeObjectiveRow(int index, String text, boolean completed) {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
			new EmptyBorder(6, 8, 6, 8)
		));
		row.setBackground(completed ? COMPLETED_BG : ACTIVE_BG);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		String prefix = completed ? "\u2713" : String.valueOf(index);
		JLabel indexLabel = new JLabel(prefix);
		indexLabel.setFont(indexLabel.getFont().deriveFont(Font.BOLD, 13f));
		if (completed) {
			indexLabel.setForeground(COMPLETED_FG);
		}
		indexLabel.setPreferredSize(new Dimension(24, 20));
		row.add(indexLabel, BorderLayout.LINE_START);

		JLabel textLabel = new JLabel("<html>" + escapeHtml(text) + "</html>");
		textLabel.setFont(textLabel.getFont().deriveFont(12f));
		if (completed) {
			textLabel.setForeground(COMPLETED_FG);
		}
		row.add(textLabel, BorderLayout.CENTER);

		return row;
	}

	private static String escapeHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	public void setVisible(boolean visible) {
		if (visible) {
			refreshObjectives();
		}
		super.setVisible(visible);
	}
}
