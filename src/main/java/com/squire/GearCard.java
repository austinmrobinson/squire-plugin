package com.squire;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.squire.ChatComponents.HeightForWidth;
import com.squire.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import net.runelite.client.ui.FontManager;

/**
 * A gear setup in the chat, drawn like the game's equipment screen (and inventory, for a full trip): each worn
 * item in its slot, total bonuses and DPS against the target. Clicking a slot lists the player's own items for it,
 * ranked by the DPS each would add; picking one re-evaluates the setup. Copy puts it on the clipboard for Inventory
 * Setups' Import.
 */
class GearCard extends Surface implements HeightForWidth
{
	/** Set by the plugin: re-evaluates a changed setup on the server. */
	static AccountApi api;

	private static final String[] SLOTS = {"head", "cape", "neck", "ammo", "weapon", "body", "shield", "legs", "hands", "feet", "ring"};
	// Where each slot sits on the game's worn-equipment screen (column, row)
	private static final int[][] POS = {{1, 0}, {0, 1}, {1, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2}, {1, 3}, {0, 4}, {1, 4}, {2, 4}};
	private static final int CELL = 36;
	private static final int GAP = 5;
	private static final Color SLOT_BG = new Color(0x2A2620);
	private static final Color SLOT_EDGE = new Color(0x4A4236);
	private static final Color QTY = new Color(0xFFFF00);

	private final JsonObject original;
	private JsonObject gear;
	private JsonObject export;
	private boolean busy;
	private boolean edited;
	/** 0: equipment, 1: inventory */
	private int tab;
	private final PlanView.Stack body = new PlanView.Stack();
	private final JLabel status = new JLabel(" ");

	GearCard(JsonObject result)
	{
		super(ChatComponents.PANEL_BG, 6, true);
		this.original = result;
		this.gear = result.getAsJsonObject("gear");
		this.export = exportOf(result);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		add(body);
		render();
	}

	private static JsonObject exportOf(JsonObject result)
	{
		return result.has("export") && result.get("export").isJsonObject() ? result.getAsJsonObject("export") : null;
	}

	@Override
	public int heightForWidth(int width)
	{
		return body.heightForWidth(width - 20) + 20;
	}

	private void render()
	{
		body.removeAll();
		String target = Ui.str(gear, "target");
		String style = Ui.str(gear, "style");
		JsonObject dps = gear.has("dps") && gear.get("dps").isJsonObject() ? gear.getAsJsonObject("dps") : null;

		// Header: the setup and what it's for on the left; DPS, max hit and kill time on the right
		JPanel head = new JPanel(new BorderLayout(4, 0));
		head.setOpaque(false);
		PlanView.Stack left = new PlanView.Stack();
		left.add(Ui.bold(Ui.str(gear, "name") + (edited ? " (edited)" : "")));
		JPanel meta = metaRow();
		if (!target.isEmpty())
		{
			meta.add(chip("gear-target", target));
		}
		if (!style.isEmpty())
		{
			meta.add(chip("gear-style", Ui.title(style)));
		}
		left.add(meta);
		head.add(left, BorderLayout.CENTER);
		if (dps != null)
		{
			PlanView.Stack right = new PlanView.Stack();
			JLabel value = Ui.bold(String.format("%.2f DPS", Ui.num(dps, "dps")));
			value.setHorizontalAlignment(JLabel.RIGHT);
			value.setToolTipText(bonusLine(gear.getAsJsonObject("bonuses"), style) + " · " + Ui.oneDecimal(Ui.num(dps, "hitChance")) + "% to hit");
			right.add(value);
			JPanel stats = metaRow();
			((FlowLayout) stats.getLayout()).setAlignment(FlowLayout.RIGHT);
			JLabel max = chip("gear-maxhit", String.valueOf((int) Ui.num(dps, "maxHit")));
			max.setToolTipText("Max hit");
			JLabel time = chip("gear-time", killTime((int) Ui.num(dps, "secondsToKill")));
			time.setToolTipText("Time to kill");
			stats.add(max);
			stats.add(time);
			right.add(stats);
			right.setPreferredSize(new Dimension(Math.max(value.getPreferredSize().width, stats.getPreferredSize().width), right.getPreferredSize().height));
			head.add(right, BorderLayout.EAST);
		}
		body.add(head);

		// Equipment | Inventory
		JsonArray inv = gear.has("inventory") && gear.get("inventory").isJsonArray() ? gear.getAsJsonArray("inventory") : null;
		ActivityCharts.Segmented tabs = new ActivityCharts.Segmented(new String[]{"Equipment", "Inventory"}, tab, new Dimension(200, 32), i ->
		{
			tab = i;
			render();
		});
		body.add(tabs, 16);

		if (tab == 0)
		{
			JPanel eqRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			eqRow.setOpaque(false);
			eqRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 16, 0));
			eqRow.add(new Equipment());
			body.add(eqRow, 0);
		}
		else if (inv != null && inv.size() > 0)
		{
			JPanel invRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			invRow.setOpaque(false);
			invRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 16, 0));
			invRow.add(new Inventory(inv));
			body.add(invRow, 0);
		}
		else
		{
			JLabel none = Ui.small("No inventory in this setup yet.");
			none.setHorizontalAlignment(JLabel.CENTER);
			body.add(none, 16);
			JLabel full = PlanView.link("Make it a full trip setup");
			full.setHorizontalAlignment(JLabel.CENTER);
			full.setForeground(ChatComponents.ACCENT.brighter());
			full.addMouseListener(PlanView.click(() -> Ui.askSquire.accept(
				"Make this into a full inventory setup" + (target.isEmpty() ? "" : " for " + target) + ": " + wornList())));
			body.add(full, 6);
			body.add((JComponent) javax.swing.Box.createVerticalStrut(16), 0);
		}

		// The evaluator's top upgrades for this target (from the setup as first built; swaps don't re-run it)
		JsonObject first = original.getAsJsonObject("gear");
		JsonArray ups = first.has("upgrades") && first.get("upgrades").isJsonArray() ? first.getAsJsonArray("upgrades") : null;
		if (tab == 0 && ups != null && ups.size() > 0)
		{
			JLabel title = Ui.small("NEXT UPGRADES");
			title.setForeground(ChatComponents.MUTED);
			body.add(title, 0);
			for (JsonElement el : ups)
			{
				body.add(new Upgrade(el.getAsJsonObject(), target), 6);
			}
			body.add((JComponent) javax.swing.Box.createVerticalStrut(16), 0);
		}

		// Copy to Inventory Setups (full width), and reset after edits
		ExportCards.PillButton copy = new ExportCards.PillButton("Copy to Inventory Setups");
		copy.setEnabled(export != null && !busy);
		copy.addActionListener(e ->
		{
			if (export != null)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Ui.str(export, "text")), null);
				status.setText("Copied. Import it in Inventory Setups.");
				render();
			}
		});
		JPanel actions = new JPanel(new BorderLayout());
		actions.setOpaque(false);
		actions.add(copy, BorderLayout.CENTER);
		body.add(actions, 0);
		JPanel foot = new JPanel(new BorderLayout(8, 0));
		foot.setOpaque(false);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ChatComponents.MUTED);
		foot.add(status, BorderLayout.CENTER);
		if (edited)
		{
			JLabel reset = PlanView.link("Reset");
			reset.addMouseListener(PlanView.click(() ->
			{
				gear = original.getAsJsonObject("gear");
				export = exportOf(original);
				edited = false;
				status.setText(" ");
				render();
			}));
			foot.add(reset, BorderLayout.EAST);
		}
		if (edited || !" ".equals(status.getText()))
		{
			body.add(foot, 6);
		}
		body.revalidate();
		body.repaint();
		Container p = getParent();
		if (p != null)
		{
			p.revalidate();
			p.repaint();
		}
	}

	private static JPanel metaRow()
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		return row;
	}

	/** A small muted label with a 12px pixel icon, like "(fist) Vorkath" or "(clock) 2:02". */
	private static JLabel chip(String icon, String text)
	{
		JLabel l = new JLabel(text, SvgIcon.load(icon, 12, ChatComponents.MUTED), JLabel.LEFT);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ChatComponents.MUTED);
		l.setIconTextGap(2);
		return l;
	}

	private static String killTime(int seconds)
	{
		return seconds >= 60 ? String.format("%d:%02d", seconds / 60, seconds % 60) : seconds + "s";
	}

	private String wornList()
	{
		StringBuilder sb = new StringBuilder();
		JsonObject eq = gear.getAsJsonObject("equipment");
		for (String slot : SLOTS)
		{
			JsonElement e = eq.get(slot);
			if (e != null && e.isJsonObject())
			{
				sb.append(sb.length() == 0 ? "" : ", ").append(Ui.str(e.getAsJsonObject(), "name"));
			}
		}
		return sb.toString();
	}

	private static String bonusLine(JsonObject b, String style)
	{
		JsonObject atk = b.getAsJsonObject("attack"), other = b.getAsJsonObject("other");
		switch (style)
		{
			case "ranged":
				return "Ranged +" + (int) Ui.num(atk, "ranged") + " · Ranged str +" + (int) Ui.num(other, "ranged_str") + " · Prayer +" + (int) Ui.num(other, "prayer");
			case "magic":
				return "Magic +" + (int) Ui.num(atk, "magic") + " · Magic dmg +" + Ui.oneDecimal(Ui.num(other, "magic_str")) + "% · Prayer +" + (int) Ui.num(other, "prayer");
			default:
				int best = (int) Math.max(Ui.num(atk, "stab"), Math.max(Ui.num(atk, "slash"), Ui.num(atk, "crush")));
				return "Attack +" + best + " · Str +" + (int) Ui.num(other, "str") + " · Prayer +" + (int) Ui.num(other, "prayer");
		}
	}

	/** Swap a slot (null empties it) and re-evaluate on the server. */
	private void swap(String slot, String itemName, boolean twoHanded)
	{
		if (api == null || busy)
		{
			return;
		}
		JsonObject request = gear.getAsJsonObject("request").deepCopy();
		JsonObject eq = request.has("equipment") && request.get("equipment").isJsonObject() ? request.getAsJsonObject("equipment") : new JsonObject();
		if (itemName == null)
		{
			eq.add(slot, JsonNull.INSTANCE);
		}
		else
		{
			eq.addProperty(slot, itemName);
		}
		if (twoHanded)
		{
			eq.add("shield", JsonNull.INSTANCE);
		}
		request.add("equipment", eq);
		busy = true;
		status.setText("Working it out...");
		api.gear(request, r -> SwingUtilities.invokeLater(() ->
		{
			busy = false;
			if (r.json != null && r.json.has("gear"))
			{
				gear = r.json.getAsJsonObject("gear");
				export = exportOf(r.json);
				edited = true;
				status.setText(" ");
			}
			else
			{
				status.setText(r.error != null ? r.error : "Couldn't update the setup.");
			}
			render();
		}));
	}

	// ---- Upgrades

	/** "+9.9% DPS · 3.6 h to get": what one upgrade adds and what it costs. */
	static String upgradeLine(JsonObject u)
	{
		// Nothing at this boss but time saved over the run: the gain is at another boss of the same activity
		boolean elsewhere = Ui.num(u, "dpsGainPct") <= 0 && Ui.num(u, "secondsSaved") > 0;
		StringBuilder sb = new StringBuilder(elsewhere
			? "Saves " + Ui.oneDecimal(Ui.num(u, "secondsSaved")) + "s a run"
			: String.format("+%s%% DPS", Ui.oneDecimal(Ui.num(u, "dpsGainPct"))));
		if (u.has("blocked") && !u.get("blocked").isJsonNull())
		{
			return sb.append(" · needs ").append(Ui.str(u, "blocked").replaceAll(" to make .*", "")).toString();
		}
		if (u.has("hours") && !u.get("hours").isJsonNull())
		{
			double h = Ui.num(u, "hours");
			sb.append(" · ").append(h <= 0 ? "no extra time" : h < 1 ? Math.max(1, Math.round(h * 60)) + " min to get" : (h < 10 ? Ui.oneDecimal(h) : String.valueOf(Math.round(h))) + " h to get");
		}
		return sb.toString();
	}

	/** "Pays off after 1,241 kills", when getting it takes time that kills can earn back; otherwise null. */
	static String payoffLine(JsonObject u)
	{
		boolean timed = u.has("hours") && !u.get("hours").isJsonNull() && Ui.num(u, "hours") > 0;
		boolean blocked = u.has("blocked") && !u.get("blocked").isJsonNull();
		if (!timed || blocked || !u.has("paysOffAfter") || u.get("paysOffAfter").isJsonNull())
		{
			return null;
		}
		return "Pays off after " + String.format("%,d", (long) Ui.num(u, "paysOffAfter")) + " kills";
	}

	/** One upgrade: the item, its name, and what it adds against what it costs. Click to ask Squire for the working. */
	private static final class Upgrade extends JComponent
	{
		private final int H;
		private final String payoff;
		private final JsonObject u;
		private boolean hover;

		Upgrade(JsonObject u, String target)
		{
			this.u = u;
			this.payoff = payoffLine(u);
			this.H = payoff == null ? 34 : 47;
			setToolTipText(Ui.str(u, "name") + ": " + upgradeLine(u) + (payoff == null ? "" : ". " + payoff));
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(10, H));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					Ui.askSquire.accept("Is the " + Ui.str(u, "name") + " worth getting" + (target.isEmpty() ? "" : " for " + target)
						+ "? Show how long it takes to get, what it changes, and when it pays off.");
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(hover ? Tokens.COLOR_SURFACE_HOVER : Tokens.COLOR_SURFACE_CARD);
			g2.fillRect(0, 0, getWidth(), getHeight());
			BufferedImage img = Crest.itemImage((int) Ui.num(u, "id"), this);
			if (img != null)
			{
				g2.drawImage(img, 3 + (32 - img.getWidth()) / 2, (H - img.getHeight()) / 2, null);
			}
			int x = 40, w = getWidth() - x - 6;
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(Tokens.COLOR_TEXT_BODY);
			g2.drawString(clip(g2, Ui.str(u, "name"), w), x, 14);
			g2.setColor(ChatComponents.MUTED);
			g2.drawString(clip(g2, upgradeLine(u), w), x, 27);
			if (payoff != null)
			{
				g2.drawString(clip(g2, payoff, w), x, 40);
			}
			g2.dispose();
		}

		private static String clip(Graphics2D g, String s, int width)
		{
			java.awt.FontMetrics fm = g.getFontMetrics();
			if (fm.stringWidth(s) <= width)
			{
				return s;
			}
			while (s.length() > 1 && fm.stringWidth(s + "...") > width)
			{
				s = s.substring(0, s.length() - 1);
			}
			return s + "...";
		}
	}

	// ---- The equipment screen

	private final class Equipment extends JComponent
	{
		private int hover = -1;

		Equipment()
		{
			setPreferredSize(new Dimension(CELL * 3 + GAP * 2, CELL * 5 + GAP * 4));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter m = new MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					int i = slotAt(e.getX(), e.getY());
					if (i != hover)
					{
						hover = i;
						setToolTipText(i < 0 ? null : tooltip(i));
						repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = -1;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
					if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
					{
						return;
					}
					int i = slotAt(e.getX(), e.getY());
					if (i >= 0)
					{
						menu(i).show(Equipment.this, e.getX(), e.getY());
					}
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
		}

		private int slotAt(int x, int y)
		{
			for (int i = 0; i < SLOTS.length; i++)
			{
				int sx = POS[i][0] * (CELL + GAP), sy = POS[i][1] * (CELL + GAP);
				if (x >= sx && x < sx + CELL && y >= sy && y < sy + CELL)
				{
					return i;
				}
			}
			return -1;
		}

		private JsonObject worn(int i)
		{
			JsonElement e = gear.getAsJsonObject("equipment").get(SLOTS[i]);
			return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		}

		private String tooltip(int i)
		{
			JsonObject w = worn(i);
			return (w == null ? "Empty " + SLOTS[i] + " slot" : Ui.str(w, "name")) + " (click to swap)";
		}

		private JPopupMenu menu(int i)
		{
			JPopupMenu menu = new JPopupMenu();
			String slot = SLOTS[i];
			JsonElement alts = gear.has("alternatives") && gear.get("alternatives").isJsonObject() ? gear.getAsJsonObject("alternatives").get(slot) : null;
			boolean any = false;
			if (alts != null && alts.isJsonArray())
			{
				for (JsonElement a : alts.getAsJsonArray())
				{
					JsonObject o = a.getAsJsonObject();
					String name = Ui.str(o, "name");
					String change = "";
					if (o.has("dpsChange") && !o.get("dpsChange").isJsonNull())
					{
						double d = o.get("dpsChange").getAsDouble();
						change = String.format("   %s%.2f DPS", d >= 0 ? "+" : "", d);
					}
					JMenuItem item = new JMenuItem(name + change);
					BufferedImage img = Crest.itemImage(o.get("id").getAsInt(), Equipment.this);
					if (img != null)
					{
						item.setIcon(new ImageIcon(img.getScaledInstance(img.getWidth() * 2 / 3, img.getHeight() * 2 / 3, java.awt.Image.SCALE_FAST)));
					}
					boolean twoHanded = o.has("twoHanded") && o.get("twoHanded").getAsBoolean();
					item.addActionListener(e -> swap(slot, name, twoHanded));
					menu.add(item);
					any = true;
				}
			}
			if (!any)
			{
				JMenuItem none = new JMenuItem("You don't own anything else for this slot");
				none.setEnabled(false);
				menu.add(none);
			}
			if (worn(i) != null)
			{
				menu.addSeparator();
				JMenuItem remove = new JMenuItem("Remove");
				remove.addActionListener(e -> swap(slot, null, false));
				menu.add(remove);
			}
			return menu;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			for (int i = 0; i < SLOTS.length; i++)
			{
				int x = POS[i][0] * (CELL + GAP), y = POS[i][1] * (CELL + GAP);
				paintSlot(g2, x, y, i == hover);
				JsonObject w = worn(i);
				if (w != null)
				{
					BufferedImage img = Crest.itemImage(w.get("id").getAsInt(), this);
					if (img != null)
					{
						g2.drawImage(img, x + (CELL - img.getWidth()) / 2, y + (CELL - img.getHeight()) / 2, null);
					}
				}
			}
			g2.dispose();
		}
	}

	/** A sunken slot like the design's: dark fill, grey border, bronze stepped corners, lit from the bottom right. */
	private static void paintSlot(Graphics2D g, int x, int y, boolean hover)
	{
		g.setColor(hover ? ChatComponents.HOVER_BG : ChatComponents.BASE_BG);
		Pixel.fill(g, x, y, CELL, CELL, 4);
		g.setColor(ChatComponents.BORDER);
		Pixel.draw(g, x, y, CELL, CELL, 4);
		// The stepped corners in bronze
		java.awt.Shape clip = g.getClip();
		g.setColor(SLOT_EDGE);
		for (int[] c : new int[][]{{x, y}, {x + CELL - 4, y}, {x, y + CELL - 4}, {x + CELL - 4, y + CELL - 4}})
		{
			g.setClip(clip);
			g.clipRect(c[0], c[1], 4, 4);
			Pixel.draw(g, x, y, CELL, CELL, 4);
		}
		g.setClip(clip);
		Pixel.bevel(g, x + 1, y + 1, CELL - 2, CELL - 2, 3, new Color(0, 0, 0, 61), new Color(255, 255, 255, 18));
	}

	// ---- The inventory

	private static final class Inventory extends JComponent
	{
		private static final int W = 36, H = 32;
		private final JsonArray items;

		Inventory(JsonArray items)
		{
			this.items = items;
			setPreferredSize(new Dimension(W * 4 + 8, H * 7 + 8));
		}

		@Override
		public void addNotify()
		{
			super.addNotify();
			ToolTipManager.sharedInstance().registerComponent(this);
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int col = (e.getX() - 4) / W, row = (e.getY() - 4) / H, i = row * 4 + col;
			if (col < 0 || col > 3 || i < 0 || i >= items.size() || !items.get(i).isJsonObject())
			{
				return null;
			}
			JsonObject o = items.get(i).getAsJsonObject();
			long q = (long) Ui.num(o, "quantity");
			return Ui.str(o, "name") + (q > 1 ? " × " + q : "");
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			// The inventory's panel, in the same style as the equipment slots
			g2.setColor(ChatComponents.BASE_BG);
			Pixel.fill(g2, 0, 0, getWidth(), getHeight(), 6);
			g2.setColor(ChatComponents.BORDER);
			Pixel.draw(g2, 0, 0, getWidth(), getHeight(), 6);
			Pixel.bevel(g2, 1, 1, getWidth() - 2, getHeight() - 2, 5, new Color(0, 0, 0, 61), new Color(255, 255, 255, 18));
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g2.getFontMetrics();
			for (int i = 0; i < Math.min(28, items.size()); i++)
			{
				if (!items.get(i).isJsonObject())
				{
					continue;
				}
				JsonObject o = items.get(i).getAsJsonObject();
				int x = 4 + (i % 4) * W, y = 4 + (i / 4) * H;
				BufferedImage img = Crest.itemImage(o.get("id").getAsInt(), this);
				if (img != null)
				{
					g2.drawImage(img, x + (W - img.getWidth()) / 2, y + (H - img.getHeight()) / 2, null);
				}
				long q = (long) Ui.num(o, "quantity");
				if (q > 1)
				{
					String s = q >= 10_000_000 ? q / 1_000_000 + "M" : q >= 100_000 ? q / 1000 + "K" : String.valueOf(q);
					g2.setColor(Color.BLACK);
					g2.drawString(s, x + 1, y + fm.getAscent());
					g2.setColor(QTY);
					g2.drawString(s, x, y + fm.getAscent() - 1);
				}
			}
			g2.dispose();
		}
	}
}
