package com.squire;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * An iOS-style navigation bar: the page's title centred, a back arrow to the page above on the left, and the page's
 * actions on the right. Right-click or long-press the back arrow to jump anywhere up the stack.
 */
class NavBar extends JPanel
{
	static final int HEIGHT = 36;
	private static final int GAP = 6;
	private static final int LONG_PRESS_MS = 450;

	/** A page up the stack: its name and how to go there. */
	static final class Crumb
	{
		final String title;
		final Runnable go;

		Crumb(String title, Runnable go)
		{
			this.title = title;
			this.go = go;
		}
	}

	private final javax.swing.JButton back = ChatComponents.iconButton("arrow-left", "Back");
	private final JLabel title = new JLabel("", SwingConstants.CENTER);
	private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
	/** Ancestors, nearest first. */
	private List<Crumb> stack = List.of();
	private String backText = "";
	private boolean longPressed;

	NavBar()
	{
		setOpaque(false);
		setLayout(new BarLayout());
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		actions.setOpaque(false);
		add(back);
		add(title);
		add(actions);
		installBackGestures();
	}

	/** Show a page: its title, the pages above it (nearest first; empty for none) and its action buttons. */
	void set(String pageTitle, List<Crumb> ancestors, List<? extends JComponent> pageActions)
	{
		title.setText(pageTitle);
		title.setToolTipText(pageTitle);
		stack = ancestors;
		backText = ancestors.isEmpty() ? "" : ancestors.get(0).title;
		back.setToolTipText(ancestors.isEmpty() ? null : "Back to " + backText + (ancestors.size() > 1 ? " (right-click for more)" : ""));
		back.setVisible(!ancestors.isEmpty());
		actions.removeAll();
		pageActions.forEach(actions::add);
		revalidate();
		repaint();
	}

	void setTitle(String pageTitle)
	{
		title.setText(pageTitle);
		title.setToolTipText(pageTitle);
		revalidate();
	}

	private void installBackGestures()
	{
		Timer hold = new Timer(LONG_PRESS_MS, e ->
		{
			longPressed = true;
			showStack();
		});
		hold.setRepeats(false);
		back.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				longPressed = false;
				if (SwingUtilities.isLeftMouseButton(e))
				{
					hold.restart();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				hold.stop();
				if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
				{
					showStack();
				}
				else if (!longPressed && back.contains(e.getPoint()) && !stack.isEmpty())
				{
					stack.get(0).go.run();
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hold.stop();
			}
		});
	}

	/** Every page up the stack, nearest first; pick one to jump straight there. */
	private void showStack()
	{
		if (stack.isEmpty())
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		for (Crumb c : stack)
		{
			JMenuItem item = new JMenuItem(c.title);
			item.setFont(FontManager.getRunescapeFont());
			item.addActionListener(e -> c.go.run());
			menu.add(item);
		}
		menu.show(back, 0, back.getHeight());
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(super.getPreferredSize().width, HEIGHT);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, HEIGHT);
	}

	/** Title centred on the whole bar (not between the sides), shortened if it would reach either side. */
	private final class BarLayout implements LayoutManager
	{
		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			return new Dimension(100, HEIGHT);
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return new Dimension(0, HEIGHT);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			int w = parent.getWidth(), h = parent.getHeight();
			int rw = actions.getPreferredSize().width;
			actions.setBounds(w - rw, (h - actions.getPreferredSize().height) / 2, rw, actions.getPreferredSize().height);

			int lw = back.isVisible() ? back.getPreferredSize().width : 0;
			int tw = title.getPreferredSize().width;
			int side = Math.max(lw, rw);
			int titleW = Math.max(0, Math.min(tw, w - 2 * side - 2 * GAP));
			title.setBounds((w - titleW) / 2, 0, titleW, h);
			back.setBounds(0, (h - back.getPreferredSize().height) / 2, lw, back.getPreferredSize().height);
		}
	}
}
