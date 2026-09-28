package com.osrssync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.osrssync.ChatComponents.HeightForWidth;
import com.osrssync.ChatComponents.Surface;
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
		body.add(Ui.bold(Ui.str(gear, "name")));
		String target = Ui.str(gear, "target");
		String style = Ui.str(gear, "style");
		body.add(Ui.small((target.isEmpty() ? "" : "vs " + target + " · ") + Ui.title(style) + (edited ? " · edited" : "")), 2);

		JsonObject dps = gear.has("dps") && gear.get("dps").isJsonObject() ? gear.getAsJsonObject("dps") : null;
		if (dps != null)
		{
			JLabel line = Ui.bold(String.format("%.2f DPS", Ui.num(dps, "dps")));
			JPanel row = new JPanel(new BorderLayout(8, 0));
			row.setOpaque(false);
			row.add(line, BorderLayout.WEST);
			row.add(Ui.small("max " + (int) Ui.num(dps, "maxHit") + " · " + Ui.oneDecimal(Ui.num(dps, "hitChance")) + "% · "
				+ killTime((int) Ui.num(dps, "secondsToKill")) + " a kill"), BorderLayout.CENTER);
			body.add(row, 8);
		}

		// The equipment screen, centred
		JPanel eqRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		eqRow.setOpaque(false);
		eqRow.add(new Equipment());
		body.add(eqRow, 10);

		JLabel bonus = Ui.small(bonusLine(gear.getAsJsonObject("bonuses"), style));
		bonus.setHorizontalAlignment(JLabel.CENTER);
		body.add(bonus, 8);

		JsonArray inv = gear.has("inventory") && gear.get("inventory").isJsonArray() ? gear.getAsJsonArray("inventory") : null;
		if (inv != null && inv.size() > 0)
		{
			JPanel invRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			invRow.setOpaque(false);
			invRow.add(new Inventory(inv));
			body.add(invRow, 12);
		}

		// Copy to Inventory Setups, and reset after edits
		JPanel actions = new JPanel(new BorderLayout(8, 0));
		actions.setOpaque(false);
		ExportCards.PillButton copy = new ExportCards.PillButton("Copy to Inventory Setups");
		copy.setEnabled(export != null && !busy);
		copy.addActionListener(e ->
		{
			if (export != null)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Ui.str(export, "text")), null);
				status.setText("Copied. In Inventory Setups, click Import and paste.");
			}
		});
		actions.add(copy, BorderLayout.WEST);
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
			actions.add(reset, BorderLayout.EAST);
		}
		body.add(actions, 12);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ChatComponents.MUTED);
		body.add(status, 4);
		if (inv == null || inv.size() == 0)
		{
			JLabel full = PlanView.link("Make it a full trip setup");
			full.setHorizontalAlignment(JLabel.LEFT);
			full.setForeground(ChatComponents.ACCENT.brighter());
			full.addMouseListener(PlanView.click(() -> Ui.askSquire.accept(
				"Make this into a full inventory setup" + (target.isEmpty() ? "" : " for " + target) + ": " + wornList())));
			body.add(full, 6);
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
				public void mouseClicked(MouseEvent e)
				{
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

	/** A sunken stone slot, like the game's worn-equipment boxes. */
	private static void paintSlot(Graphics2D g, int x, int y, boolean hover)
	{
		g.setColor(hover ? SLOT_BG.brighter() : SLOT_BG);
		Pixel.fill(g, x, y, CELL, CELL, 4);
		g.setColor(SLOT_EDGE);
		Pixel.draw(g, x, y, CELL, CELL, 4);
		Pixel.bevel(g, x + 1, y + 1, CELL - 2, CELL - 2, 3, ChatComponents.CARD_DARK, ChatComponents.CARD_LIGHT);
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
			// The inventory's stone background
			g2.setColor(SLOT_BG);
			Pixel.fill(g2, 0, 0, getWidth(), getHeight(), 6);
			g2.setColor(SLOT_EDGE);
			Pixel.draw(g2, 0, 0, getWidth(), getHeight(), 6);
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
