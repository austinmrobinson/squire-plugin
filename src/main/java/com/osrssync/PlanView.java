package com.osrssync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.HeightForWidth;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import com.osrssync.WelcomeView.Wrapped;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/**
 * The player's plan: a vertical chart of checkpoints (like a Ladlor chart), each a tile on a rail. Done checkpoints
 * fill in, the current one is outlined, and clicking a tile opens its goals (checked from the account) and steps
 * (ticked off here). Squire makes and edits the plan in chat; this page and the Home card show it.
 */
class PlanView extends JPanel
{
	static final Color DONE = new Color(0x5FB548);
	private static final int RAIL = 44;
	private static final int NODE = 32;

	private final AccountApi api;
	private final MessageList list = new MessageList(null, 0);
	private final Set<String> expanded = new HashSet<>();
	private JsonObject plan;
	private String error;
	private boolean loaded;
	private Consumer<String> askSquire = text -> {};
	private Runnable onChanged = () -> {};

	PlanView(AccountApi api)
	{
		super(new BorderLayout());
		this.api = api;
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, FloatingAsk.CLEARANCE, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);
		render();
	}

	/** Chat hand-off ("Help me make a plan") and a hook for Home to redraw its card when the plan changes. */
	void setActions(Consumer<String> askSquire, Runnable onChanged)
	{
		this.askSquire = askSquire;
		this.onChanged = onChanged;
	}

	JsonObject plan()
	{
		return plan;
	}

	/**
	 * The plan as context for a question asked from this page: a tile on the player's message ("PLAN" with the
	 * current checkpoint's item), and a markdown summary Squire reads (it can also call get_plan for detail).
	 */
	Attachment contextAttachment()
	{
		if (plan == null)
		{
			return null;
		}
		StringBuilder md = new StringBuilder("# Plan: ").append(Ui.str(plan, "title")).append("\n\n");
		String summary = Ui.str(plan, "summary");
		if (!summary.isEmpty())
		{
			md.append(summary).append("\n\n");
		}
		md.append((int) Ui.num(plan, "completed")).append(" of ").append((int) Ui.num(plan, "total")).append(" checkpoints done.\n\n");
		JsonArray cps = plan.getAsJsonArray("checkpoints");
		int current = plan.has("current") && !plan.get("current").isJsonNull() ? plan.get("current").getAsInt() : -1;
		int iconId = -1;
		for (int i = 0; i < cps.size(); i++)
		{
			JsonObject cp = cps.get(i).getAsJsonObject();
			boolean done = cp.get("complete").getAsBoolean();
			md.append(i + 1).append(". [").append(done ? "x" : " ").append("] ").append(Ui.str(cp, "title"))
				.append(done ? " (done)" : i == current ? " (current, " + Math.round(Ui.num(cp, "progress") * 100) + "%)" : "").append("\n");
			if (i == current)
			{
				for (JsonElement e : cp.getAsJsonArray("goals"))
				{
					JsonObject g = e.getAsJsonObject();
					md.append("   - goal: ").append(Ui.str(g, "label")).append(g.get("met").getAsBoolean() ? " (met)" : " (not yet)").append("\n");
				}
				for (JsonElement e : cp.getAsJsonArray("steps"))
				{
					JsonObject s = e.getAsJsonObject();
					md.append("   - step: ").append(Ui.str(s, "text")).append(s.get("done").getAsBoolean() ? " (done)" : "").append("\n");
				}
				JsonElement icon = cp.get("icon");
				if (icon != null && icon.isJsonObject() && icon.getAsJsonObject().has("id") && !icon.getAsJsonObject().get("id").isJsonNull())
				{
					iconId = icon.getAsJsonObject().get("id").getAsInt();
				}
			}
		}
		BufferedImage img = iconId > 0 ? Crest.itemImage(iconId, this) : null;
		return Attachment.context("Plan", "Plan: " + Ui.str(plan, "title"), md.toString(), img != null ? img : SquireIcon.create(26));
	}

	boolean loaded()
	{
		return loaded;
	}

	void refresh()
	{
		api.plan(r -> SwingUtilities.invokeLater(() -> show(r.json == null ? null : r.json, r.error)));
	}

	/** Show a reply from /api/plan ({plan: ...}), or an error. */
	void show(JsonObject reply, String err)
	{
		loaded = true;
		error = err;
		if (reply != null)
		{
			JsonElement p = reply.get("plan");
			plan = p == null || p.isJsonNull() ? null : p.getAsJsonObject();
			if (plan != null)
			{
				saveCopy(plan);
			}
		}
		// Open the current checkpoint the first time a plan shows
		if (plan != null && expanded.isEmpty() && plan.has("current") && !plan.get("current").isJsonNull())
		{
			int current = plan.get("current").getAsInt();
			expanded.add(Ui.str(plan.getAsJsonArray("checkpoints").get(current).getAsJsonObject(), "id"));
		}
		render();
		onChanged.run();
	}

	private void checkpointOp(String name, String checkpoint)
	{
		JsonObject op = new JsonObject();
		op.addProperty("op", name);
		op.addProperty("checkpoint", checkpoint);
		edit(op);
	}

	private void edit(JsonObject op)
	{
		api.editPlan(op, r -> SwingUtilities.invokeLater(() ->
		{
			if (r.json != null)
			{
				show(r.json, null);
			}
			else if (r.error != null)
			{
				// e.g. starting from the guide before the bank has synced
				show(null, r.error);
			}
		}));
	}

	private void render()
	{
		list.removeAll();
		if (plan == null)
		{
			list.add(ChatComponents.place(emptyCard(), Align.FILL, 0));
			list.revalidate();
			list.repaint();
			return;
		}

		// Title, summary and overall progress
		Surface head = HomeView.homeCard();
		head.setLayout(new BorderLayout());
		Stack top = new Stack();
		JLabel title = Ui.bold(Ui.str(plan, "title"));
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		top.add(title);
		String summary = Ui.str(plan, "summary");
		if (!summary.isEmpty())
		{
			top.add(new Wrapped(summary, ChatComponents.MUTED, false), 4);
		}
		int completed = (int) Ui.num(plan, "completed"), total = (int) Ui.num(plan, "total");
		top.add(Ui.small(completed == total ? "All " + total + " checkpoints done" : completed + " of " + total + " checkpoints done"), 10);
		top.add(new Bar(total == 0 ? 0 : completed / (double) total, completed == total ? DONE : ChatComponents.ACCENT), 4);
		head.add(top);
		javax.swing.JButton more = ChatComponents.iconButton("more", "More");
		more.addActionListener(e -> showMenu(more));
		JPanel corner = new JPanel(new BorderLayout());
		corner.setOpaque(false);
		corner.add(more, BorderLayout.NORTH);
		head.add(corner, BorderLayout.EAST);
		list.add(ChatComponents.place(head, Align.FILL, 0));

		// The chart: one tile per checkpoint on a rail
		JsonArray cps = plan.getAsJsonArray("checkpoints");
		int current = plan.has("current") && !plan.get("current").isJsonNull() ? plan.get("current").getAsInt() : -1;
		for (int i = 0; i < cps.size(); i++)
		{
			JsonObject cp = cps.get(i).getAsJsonObject();
			boolean prevDone = i > 0 && cps.get(i - 1).getAsJsonObject().get("complete").getAsBoolean();
			list.add(ChatComponents.place(new Tile(cp, i, i == current, i == 0, i == cps.size() - 1, prevDone), Align.FILL, i == 0 ? 12 : 0));
		}

		list.revalidate();
		list.repaint();
	}

	/** The plan's "..." menu: copy its ID (to reference it when reporting a problem) or delete it. */
	private void showMenu(JComponent anchor)
	{
		javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		javax.swing.JMenuItem copyId = new javax.swing.JMenuItem("Copy plan ID");
		copyId.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new java.awt.datatransfer.StringSelection(Ui.str(plan, "id")), null));
		javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete plan");
		delete.addActionListener(e ->
		{
			int answer = javax.swing.JOptionPane.showConfirmDialog(this, "Delete \"" + Ui.str(plan, "title") + "\"? This can't be undone.",
				"Delete plan", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (answer == javax.swing.JOptionPane.OK_OPTION)
			{
				JsonObject op = new JsonObject();
				op.addProperty("op", "delete");
				edit(op);
			}
		});
		for (javax.swing.JMenuItem item : new javax.swing.JMenuItem[]{copyId, delete})
		{
			item.setFont(FontManager.getRunescapeFont());
		}
		copyId.setEnabled(!Ui.str(plan, "id").isEmpty());
		menu.add(copyId);
		menu.addSeparator();
		menu.add(delete);
		menu.show(anchor, anchor.getWidth() - menu.getPreferredSize().width, anchor.getHeight());
	}

	/** Keep a copy of each plan shown in ~/.runelite/account-sync/plans/<id>.json, like chats, so it can be looked at later. */
	private static void saveCopy(JsonObject plan)
	{
		String id = Ui.str(plan, "id");
		if (!id.matches("[A-Za-z0-9-]{4,40}"))
		{
			return;
		}
		try
		{
			java.nio.file.Path dir = new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "account-sync/plans").toPath();
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(dir.resolve(id + ".json"), plan.toString());
		}
		catch (java.io.IOException | RuntimeException ignored)
		{
			// Only a convenience copy; the server has the plan
		}
	}

	private JComponent emptyCard()
	{
		Surface c = HomeView.homeCard();
		c.setLayout(new BorderLayout());
		Stack s = new Stack();
		JLabel t = Ui.bold(loaded ? "No plan yet" : "Loading...");
		t.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		s.add(t);
		if (loaded)
		{
			s.add(new Wrapped(error != null ? error
				: "A plan is your roadmap: checkpoints like Barrows gloves, a fire cape or your first raid, with the steps to get "
				+ "there. Squire builds it from your account and ticks it off as you play.", ChatComponents.MUTED, false), 6);
			ChatComponents.AccentButton make = new ChatComponents.AccentButton("Make a plan with Squire");
			make.addActionListener(e -> askSquire.accept("Help me make a plan for my account"));
			s.add(make, 14);
			// Or start from the next steps on Squire's progression guide (Ladlor's chart and Yazi's gear progression)
			ChatComponents.AccentButton guide = new ChatComponents.AccentButton("Start from the guide", true);
			guide.setToolTipText("The next 10 steps on Squire's progression guide that you haven't done yet");
			guide.addActionListener(e ->
			{
				guide.setEnabled(false);
				guide.setText("Starting...");
				JsonObject op = new JsonObject();
				op.addProperty("op", "from_guide");
				edit(op);
			});
			s.add(guide, 8);
		}
		c.add(s);
		return c;
	}

	// ---- A checkpoint tile

	private final class Tile extends JPanel implements HeightForWidth
	{
		private final Rail rail;
		private final Stack body = new Stack();

		Tile(JsonObject cp, int index, boolean current, boolean first, boolean last, boolean prevDone)
		{
			setOpaque(false);
			String id = Ui.str(cp, "id");
			boolean complete = cp.get("complete").getAsBoolean();
			boolean skipped = cp.has("skipped") && cp.get("skipped").getAsBoolean();
			boolean outgrown = cp.has("outgrown") && cp.get("outgrown").getAsBoolean();
			boolean open = expanded.contains(id);
			rail = new Rail(cp, index, complete, current, first, last, prevDone);
			add(rail);

			Surface card = new Surface(current ? ChatComponents.PANEL_BG : ChatComponents.CARD_BG, 6, true);
			if (current)
			{
				card.border(ChatComponents.ACCENT);
			}
			card.setLayout(new BorderLayout());
			card.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
			Stack inner = new Stack();
			JLabel name = Ui.bold(Ui.str(cp, "title"));
			name.setForeground(complete || skipped ? ChatComponents.MUTED : java.awt.Color.WHITE);
			inner.add(name);
			double progress = Ui.num(cp, "progress");
			JLabel sub = Ui.small(skipped ? "Skipped" : outgrown ? "Done · you're past this" : complete ? "Done"
				: current ? "In progress · " + Math.round(progress * 100) + "%" : Math.round(progress * 100) + "%");
			sub.setForeground(complete && !skipped ? DONE : ChatComponents.MUTED);
			inner.add(sub, 2);
			if (!complete && !skipped && progress > 0)
			{
				inner.add(new Bar(progress, ChatComponents.ACCENT), 6);
			}
			if (open)
			{
				details(inner, cp, id);
			}
			card.add(inner);
			body.add(card);
			add(body);
			setLayout(new LayoutManager()
			{
				@Override
				public void addLayoutComponent(String n, Component c)
				{
				}

				@Override
				public void removeLayoutComponent(Component c)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					return new Dimension(parent.getWidth(), heightForWidth(parent.getWidth()));
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(0, 0);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					int w = parent.getWidth(), h = parent.getHeight();
					rail.setBounds(0, 0, RAIL, h);
					body.setBounds(RAIL, 0, w - RAIL, body.heightForWidth(w - RAIL));
				}
			});
			// Clicking the card (not a step) opens or closes it
			card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter toggle = click(() ->
			{
				if (!expanded.remove(id))
				{
					expanded.add(id);
				}
				render();
			});
			card.addMouseListener(toggle);
			name.addMouseListener(toggle);
			rail.addMouseListener(toggle);
		}

		/** Note, goals (checked from the account) and steps (ticked here) for an open checkpoint. */
		private void details(Stack inner, JsonObject cp, String id)
		{
			String note = Ui.str(cp, "note");
			if (!note.isEmpty())
			{
				inner.add(new Wrapped(note, ChatComponents.MUTED, false), 8);
			}
			JsonArray goals = cp.getAsJsonArray("goals");
			if (goals.size() > 0)
			{
				inner.add(Ui.sectionLabel("Goals"), 10);
				for (JsonElement e : goals)
				{
					JsonObject g = e.getAsJsonObject();
					boolean met = g.get("met").getAsBoolean();
					String progress = "";
					if (!met && g.has("have") && !g.get("have").isJsonNull() && Ui.num(g, "need") > 1)
					{
						progress = Ui.shortNumber(Ui.num(g, "have")) + " / " + Ui.shortNumber(Ui.num(g, "need"));
					}
					inner.add(checkRow(Ui.str(g, "label"), progress, met, null), 4);
				}
			}
			JsonArray steps = cp.getAsJsonArray("steps");
			if (steps.size() > 0)
			{
				inner.add(Ui.sectionLabel("Steps"), 10);
				for (JsonElement e : steps)
				{
					JsonObject s = e.getAsJsonObject();
					inner.add(checkRow(Ui.str(s, "text"), "", s.get("done").getAsBoolean(), () ->
					{
						JsonObject op = new JsonObject();
						op.addProperty("op", "toggle_step");
						op.addProperty("checkpoint", id);
						op.addProperty("step", Ui.str(s, "id"));
						edit(op);
					}), 4);
				}
			}
			boolean manual = cp.get("manuallyDone").getAsBoolean();
			boolean complete = cp.get("complete").getAsBoolean();
			boolean skipped = cp.has("skipped") && cp.get("skipped").getAsBoolean();
			JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			actions.setOpaque(false);
			if (!skipped && (manual || !complete))
			{
				JLabel mark = link(manual ? "Mark as not done" : "Mark as done");
				mark.addMouseListener(click(() -> checkpointOp("toggle_checkpoint", id)));
				actions.add(mark);
				actions.add(javax.swing.Box.createHorizontalStrut(14));
			}
			if (skipped || !complete)
			{
				// Skipping takes it off the path: it stops being current and isn't counted
				JLabel skip = link(skipped ? "Don't skip" : "Skip");
				skip.setToolTipText(skipped ? "Put this back on your path" : "Leave this out of your plan; Squire won't add it back from the guide");
				skip.addMouseListener(click(() -> checkpointOp("skip_checkpoint", id)));
				actions.add(skip);
			}
			if (actions.getComponentCount() > 0)
			{
				inner.add(actions, 10);
			}
		}

		@Override
		public int heightForWidth(int width)
		{
			return Math.max(NODE + 8, body.heightForWidth(width - RAIL) + 10);
		}
	}

	/** The rail beside a tile: a line joining the checkpoints and a pixel node with the checkpoint's item. */
	private final class Rail extends JComponent
	{
		private final boolean complete;
		private final boolean current;
		private final boolean first;
		private final boolean last;
		private final boolean prevDone;
		private final int itemId;
		private final int index;

		Rail(JsonObject cp, int index, boolean complete, boolean current, boolean first, boolean last, boolean prevDone)
		{
			this.index = index;
			this.complete = complete;
			this.current = current;
			this.first = first;
			this.last = last;
			this.prevDone = prevDone;
			JsonElement icon = cp.get("icon");
			int id = -1;
			if (icon != null && icon.isJsonObject() && icon.getAsJsonObject().has("id") && !icon.getAsJsonObject().get("id").isJsonNull())
			{
				id = icon.getAsJsonObject().get("id").getAsInt();
			}
			this.itemId = id;
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			int cx = (RAIL - 4) / 2, top = 4;
			// The line: done stretches are green, the rest dark
			if (!first)
			{
				g2.setColor(prevDone ? DONE : ChatComponents.PANEL_BG);
				g2.fillRect(cx - 1, 0, 2, top);
			}
			if (!last)
			{
				g2.setColor(complete ? DONE : ChatComponents.PANEL_BG);
				g2.fillRect(cx - 1, top + NODE, 2, getHeight() - top - NODE);
			}
			int x = cx - NODE / 2;
			g2.setColor(complete ? new Color(0x2E4A26) : current ? ChatComponents.PANEL_BG : ChatComponents.BASE_BG);
			Pixel.fill(g2, x, top, NODE, NODE, 4);
			g2.setColor(complete ? DONE : current ? ChatComponents.ACCENT : ChatComponents.BORDER);
			Pixel.draw(g2, x, top, NODE, NODE, 4);
			BufferedImage img = itemId > 0 ? Crest.itemImage(itemId, this) : null;
			if (img != null)
			{
				g2.drawImage(img, x + (NODE - img.getWidth()) / 2, top + (NODE - img.getHeight()) / 2, null);
			}
			else
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
				g2.setFont(FontManager.getRunescapeBoldFont());
				g2.setColor(complete ? DONE : Color.WHITE);
				String n = String.valueOf(index + 1);
				java.awt.FontMetrics fm = g2.getFontMetrics();
				g2.drawString(n, cx - fm.stringWidth(n) / 2, top + (NODE + fm.getAscent()) / 2 - 2);
			}
			if (complete)
			{
				// A small green tick badge in the corner
				g2.setColor(DONE);
				Pixel.fill(g2, x + NODE - 10, top + NODE - 10, 12, 12, 2);
				paintTick(g2, x + NODE - 8, top + NODE - 8, Color.WHITE);
			}
			g2.dispose();
		}
	}

	// ---- Small pieces

	private static void paintTick(Graphics2D g, int x, int y, Color c)
	{
		g.setColor(c);
		g.fillRect(x, y + 4, 2, 2);
		g.fillRect(x + 2, y + 6, 2, 2);
		g.fillRect(x + 4, y + 4, 2, 2);
		g.fillRect(x + 6, y + 2, 2, 2);
	}

	/** A pixel checkbox, ticked or empty. */
	private static final class Check implements Icon
	{
		private final boolean on;

		Check(boolean on)
		{
			this.on = on;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(on ? DONE : ChatComponents.BASE_BG);
			Pixel.fill(g2, x, y, 12, 12, 2);
			g2.setColor(on ? DONE.darker() : ChatComponents.BORDER);
			Pixel.draw(g2, x, y, 12, 12, 2);
			if (on)
			{
				paintTick(g2, x + 2, y + 1, Color.WHITE);
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 12;
		}

		@Override
		public int getIconHeight()
		{
			return 12;
		}
	}

	/** Checkbox, wrapped text and an optional muted note on the right ("85 / 90"); clickable when onToggle is set. */
	private static JComponent checkRow(String text, String right, boolean on, Runnable onToggle)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0))
		{
			@Override
			public Dimension getPreferredSize()
			{
				int w = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 200;
				Component center = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
				int rightW = right.isEmpty() ? 0 : ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.EAST).getPreferredSize().width + 8;
				int h = ((Wrapped) center).heightForWidth(Math.max(40, w - 20 - rightW));
				return new Dimension(w, Math.max(14, h));
			}
		};
		row.setOpaque(false);
		JLabel box = new JLabel(new Check(on));
		box.setVerticalAlignment(JLabel.TOP);
		box.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
		row.add(box, BorderLayout.WEST);
		Wrapped label = new Wrapped(text, on ? ChatComponents.MUTED : new Color(0xC8C8C8), false);
		row.add(label, BorderLayout.CENTER);
		if (!right.isEmpty())
		{
			JLabel r = Ui.small(right);
			r.setVerticalAlignment(JLabel.TOP);
			row.add(r, BorderLayout.EAST);
		}
		if (onToggle != null)
		{
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter m = click(onToggle);
			row.addMouseListener(m);
			box.addMouseListener(m);
			label.addMouseListener(m);
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}
		return row;
	}

	/** A thin pixel progress bar. */
	static final class Bar extends JComponent
	{
		private final double value;
		private final Color color;

		Bar(double value, Color color)
		{
			this.value = Math.max(0, Math.min(1, value));
			this.color = color;
			setPreferredSize(new Dimension(100, 6));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(ChatComponents.BASE_BG);
			Pixel.fill(g2, 0, 0, getWidth(), getHeight(), 2);
			int w = (int) Math.round(getWidth() * value);
			if (w > 0)
			{
				g2.setColor(color);
				Pixel.fill(g2, 0, 0, Math.max(w, 4), getHeight(), 2);
			}
			g2.dispose();
		}
	}

	/** Vertical stack whose height follows its width (wrapped text inside). Children fill the width. */
	static final class Stack extends JPanel implements HeightForWidth
	{
		private final java.util.List<Integer> gaps = new java.util.ArrayList<>();

		Stack()
		{
			setOpaque(false);
			setLayout(new LayoutManager()
			{
				@Override
				public void addLayoutComponent(String n, Component c)
				{
				}

				@Override
				public void removeLayoutComponent(Component c)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					int w = parent.getWidth() > 0 ? parent.getWidth() : 200;
					return new Dimension(w, heightForWidth(w));
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(0, 0);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					int w = parent.getWidth(), y = 0;
					for (int i = 0; i < getComponentCount(); i++)
					{
						Component c = getComponent(i);
						y += gaps.get(i);
						int h = heightOf(c, w);
						c.setBounds(0, y, w, h);
						y += h;
					}
				}
			});
		}

		void add(JComponent c, int gapTop)
		{
			gaps.add(gapTop);
			super.add(c);
		}

		@Override
		public Component add(Component c)
		{
			gaps.add(0);
			return super.add(c);
		}

		private static int heightOf(Component c, int w)
		{
			if (c instanceof HeightForWidth)
			{
				return ((HeightForWidth) c).heightForWidth(w);
			}
			if (c instanceof Container && ((Container) c).getComponentCount() == 1 && ((Container) c).getComponent(0) instanceof HeightForWidth)
			{
				java.awt.Insets in = ((Container) c).getInsets();
				return ((HeightForWidth) ((Container) c).getComponent(0)).heightForWidth(w - in.left - in.right) + in.top + in.bottom;
			}
			return c.getPreferredSize().height;
		}

		@Override
		public int heightForWidth(int width)
		{
			int y = 0;
			for (int i = 0; i < getComponentCount(); i++)
			{
				y += gaps.get(i) + heightOf(getComponent(i), width);
			}
			return y;
		}
	}

	/**
	 * The plan in a chat reply: title, progress, and one row per checkpoint (its item, name, and done / next),
	 * like a list card. Clicking it opens the Plan page.
	 */
	static final class ChatCard extends Surface implements HeightForWidth
	{
		private final Stack body = new Stack();

		ChatCard(JsonObject plan, Runnable open)
		{
			super(ChatComponents.PANEL_BG, 6, true);
			setLayout(new BorderLayout());
			setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
			add(body);
			int completed = (int) Ui.num(plan, "completed"), total = (int) Ui.num(plan, "total");
			JPanel head = new JPanel(new BorderLayout(8, 0));
			head.setOpaque(false);
			head.add(Ui.bold(Ui.str(plan, "title")), BorderLayout.CENTER);
			JPanel right = new JPanel(new BorderLayout(4, 0));
			right.setOpaque(false);
			right.add(Ui.small(completed + " of " + total), BorderLayout.CENTER);
			right.add(new JLabel(SvgIcon.load("chevron-right", 16, null)), BorderLayout.EAST);
			head.add(right, BorderLayout.EAST);
			body.add(head);
			body.add(new Bar(total == 0 ? 0 : completed / (double) total, completed == total ? DONE : ChatComponents.ACCENT), 6);
			JsonArray cps = plan.getAsJsonArray("checkpoints");
			int current = plan.has("current") && !plan.get("current").isJsonNull() ? plan.get("current").getAsInt() : -1;
			for (int i = 0; i < cps.size(); i++)
			{
				body.add(row(cps.get(i).getAsJsonObject(), i, i == current), i == 0 ? 10 : 4);
			}
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter m = click(open);
			addMouseListener(m);
			for (Component c : body.getComponents())
			{
				c.addMouseListener(m);
			}
			setToolTipText("Open your plan");
		}

		private static JComponent row(JsonObject cp, int index, boolean current)
		{
			boolean done = cp.get("complete").getAsBoolean();
			JPanel r = new JPanel(new BorderLayout(8, 0));
			r.setOpaque(false);
			r.add(new MiniNode(cp, index, done, current), BorderLayout.WEST);
			JLabel name = current ? Ui.bold(Ui.str(cp, "title")) : Ui.text(Ui.str(cp, "title"), done ? ChatComponents.MUTED : new Color(0xC8C8C8));
			r.add(name, BorderLayout.CENTER);
			JLabel state = Ui.small(done ? "Done" : current ? "Next" : "");
			state.setForeground(done ? DONE : ChatComponents.ACCENT.brighter());
			r.add(state, BorderLayout.EAST);
			r.setPreferredSize(new Dimension(100, 24));
			return r;
		}

		@Override
		public int heightForWidth(int width)
		{
			return body.heightForWidth(width - 20) + 20;
		}
	}

	/** A 22px pixel node with the checkpoint's item (or its number), green when done, blue when next. */
	private static final class MiniNode extends JComponent
	{
		private final int itemId;
		private final int index;
		private final boolean done;
		private final boolean current;

		MiniNode(JsonObject cp, int index, boolean done, boolean current)
		{
			JsonElement icon = cp.get("icon");
			int id = -1;
			if (icon != null && icon.isJsonObject() && icon.getAsJsonObject().has("id") && !icon.getAsJsonObject().get("id").isJsonNull())
			{
				id = icon.getAsJsonObject().get("id").getAsInt();
			}
			this.itemId = id;
			this.index = index;
			this.done = done;
			this.current = current;
			setPreferredSize(new Dimension(24, 24));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(done ? new Color(0x2E4A26) : ChatComponents.BASE_BG);
			Pixel.fill(g2, 0, 0, 24, 24, 3);
			g2.setColor(done ? DONE : current ? ChatComponents.ACCENT : ChatComponents.BORDER);
			Pixel.draw(g2, 0, 0, 24, 24, 3);
			BufferedImage img = itemId > 0 ? Crest.itemImage(itemId, this) : null;
			if (img != null)
			{
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				int w = img.getWidth() * 2 / 3, h = img.getHeight() * 2 / 3;
				g2.drawImage(img, (24 - w) / 2, (24 - h) / 2, w, h, null);
			}
			else
			{
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(done ? DONE : Color.WHITE);
				String n = String.valueOf(index + 1);
				java.awt.FontMetrics fm = g2.getFontMetrics();
				g2.drawString(n, (24 - fm.stringWidth(n)) / 2, (24 + fm.getAscent()) / 2 - 2);
			}
			g2.dispose();
		}
	}

	static JLabel link(String text)
	{
		JLabel l = new JLabel("<html><u>" + text + "</u></html>", JLabel.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ChatComponents.MUTED);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return l;
	}

	static MouseAdapter click(Runnable r)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				r.run();
			}
		};
	}
}
