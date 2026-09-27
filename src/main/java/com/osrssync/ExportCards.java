package com.osrssync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/**
 * Cards under an answer for data the agent made for other plugins (an Inventory Setups setup, a bank tag tab,
 * ground markers). Copy puts it on the clipboard and shows where to paste it; the player imports it with that
 * plugin's own Import. Nothing is imported automatically, and nothing touches the game.
 *
 * Inventory Setups announces its setup list over the PluginMessage API, so once a copied setup shows up there the
 * card says so and can open it (the same as picking it in that plugin's panel).
 */
final class ExportCards
{
	/** Set by the plugin: opens a setup in Inventory Setups by name. */
	static Consumer<String> openSetup;

	private static final List<WeakReference<Card>> LIVE = new ArrayList<>();
	private static Set<String> knownSetups;

	static final class Export
	{
		final String kind;
		final String target;
		final String title;
		final String subtitle;
		final String text;
		final String howTo;
		final List<String> unresolved = new ArrayList<>();

		private Export(String kind, String target, String title, String subtitle, String text, String howTo)
		{
			this.kind = kind;
			this.target = target;
			this.title = title;
			this.subtitle = subtitle;
			this.text = text;
			this.howTo = howTo;
		}

		/** From a create_* tool result: { export: {...}, unresolved: [...] }. */
		static Export from(JsonObject result)
		{
			JsonElement e = result.get("export");
			if (e == null || !e.isJsonObject())
			{
				return null;
			}
			JsonObject o = e.getAsJsonObject();
			if (str(o, "text").isEmpty())
			{
				return null;
			}
			Export x = new Export(str(o, "kind"), str(o, "target"), str(o, "title"), str(o, "subtitle"), str(o, "text"), str(o, "howTo"));
			JsonElement missing = result.get("unresolved");
			if (missing != null && missing.isJsonArray())
			{
				missing.getAsJsonArray().forEach(m -> x.unresolved.add(m.getAsString()));
			}
			return x;
		}

		/** The same shape as the tool result it came from, for the chat history. */
		JsonObject toJson()
		{
			JsonObject e = new JsonObject();
			e.addProperty("kind", kind);
			e.addProperty("target", target);
			e.addProperty("title", title);
			e.addProperty("subtitle", subtitle);
			e.addProperty("text", text);
			e.addProperty("howTo", howTo);
			JsonObject o = new JsonObject();
			o.add("export", e);
			JsonArray missing = new JsonArray();
			unresolved.forEach(missing::add);
			o.add("unresolved", missing);
			return o;
		}

		/** The item shown on the card: the setup's weapon (or first item), or the tag tab's icon. */
		int iconItemId()
		{
			try
			{
				if ("bank_tag".equals(kind))
				{
					String[] parts = text.split(",");
					return parts.length > 3 ? Integer.parseInt(parts[3]) : -1;
				}
				if ("inventory_setup".equals(kind))
				{
					JsonObject setup = new JsonParser().parse(text).getAsJsonObject().getAsJsonObject("setup");
					JsonArray eq = setup.getAsJsonArray("eq");
					if (eq != null && eq.size() > 3 && eq.get(3).isJsonObject())
					{
						return eq.get(3).getAsJsonObject().get("id").getAsInt();
					}
					for (JsonArray list : new JsonArray[]{setup.getAsJsonArray("inv"), eq})
					{
						for (JsonElement s : list)
						{
							if (s.isJsonObject())
							{
								return s.getAsJsonObject().get("id").getAsInt();
							}
						}
					}
				}
			}
			catch (RuntimeException ignored)
			{
				// no icon
			}
			return -1;
		}

		/** Ground markers: the first tile's colour. */
		Color markerColor()
		{
			try
			{
				JsonArray pts = new JsonParser().parse(text).getAsJsonArray();
				String hex = pts.get(0).getAsJsonObject().get("color").getAsString();
				return new Color((int) Long.parseLong(hex.substring(1), 16), true);
			}
			catch (RuntimeException e)
			{
				return Color.YELLOW;
			}
		}
	}

	/** A card for the chat, inset from both sides by `inset`. */
	static JComponent row(Export export, int inset)
	{
		Card c = new Card(export);
		synchronized (LIVE)
		{
			LIVE.add(new WeakReference<>(c));
		}
		return new Row(c, inset);
	}

	private static final class Row extends JPanel implements ChatComponents.HeightForWidth
	{
		private final Card card;
		private final int inset;

		Row(Card card, int inset)
		{
			super(new BorderLayout());
			this.card = card;
			this.inset = inset;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(0, inset, 0, inset));
			add(card);
		}

		@Override
		public int heightForWidth(int width)
		{
			card.wrapWidth = width - inset * 2;
			return card.getPreferredSize().height;
		}
	}

	/** Inventory Setups' current setup names (from its setups-changed broadcast). Any thread. */
	static void setupsChanged(List<String> names)
	{
		SwingUtilities.invokeLater(() ->
		{
			Set<String> before = knownSetups;
			knownSetups = new HashSet<>(names);
			if (before == null)
			{
				return; // first report: nothing is new yet
			}
			List<String> added = new ArrayList<>(names);
			added.removeAll(before);
			if (added.isEmpty())
			{
				return;
			}
			synchronized (LIVE)
			{
				for (Iterator<WeakReference<Card>> it = LIVE.iterator(); it.hasNext(); )
				{
					Card card = it.next().get();
					if (card == null)
					{
						it.remove();
						continue;
					}
					for (String name : added)
					{
						// The plugin renames duplicates ("Vorkath" -> "Vorkath (1)")
						if (card.awaitingImport() && (name.equals(card.export.title) || name.startsWith(card.export.title + " (")))
						{
							card.imported(name);
							break;
						}
					}
				}
			}
		});
	}

	/** Seed the known setups without treating them as new (e.g. from get-setups at startup). */
	static void knownSetups(List<String> names)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (knownSetups == null)
			{
				knownSetups = new HashSet<>(names);
			}
		});
	}

	private static final class Card extends ChatComponents.Surface
	{
		private final Export export;
		private final PillButton copy = new PillButton("Copy");
		private final JPanel after = new JPanel();
		private final javax.swing.JTextArea status = new javax.swing.JTextArea()
		{
			// Wrap to the card's width (layouts ask for the height before the width is settled)
			@Override
			public Dimension getPreferredSize()
			{
				int cardWidth = wrapWidth > 0 ? wrapWidth : Card.this.getWidth();
				int w = cardWidth - 16 - (open.isVisible() ? open.getPreferredSize().width + 6 : 0);
				if (w <= 0)
				{
					return super.getPreferredSize();
				}
				setSize(w, Short.MAX_VALUE);
				return new Dimension(w, super.getPreferredSize().height);
			}
		};
		private final PillButton open = new PillButton("Open");
		private boolean copied;
		private String importedAs;
		/** The width the chat will give this card (set before it asks for the height). */
		int wrapWidth;

		Card(Export export)
		{
			super(ChatComponents.PANEL_BG, 8, true);
			this.export = export;
			setLayout(new BorderLayout(0, 0));
			setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

			JPanel top = new JPanel(new BorderLayout(8, 0));
			top.setOpaque(false);
			top.add(new IconWell(export), BorderLayout.WEST);
			JPanel words = new JPanel();
			words.setOpaque(false);
			words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
			JLabel title = Ui.bold(export.title);
			JLabel sub = Ui.small(export.subtitle);
			title.setAlignmentX(Component.LEFT_ALIGNMENT);
			sub.setAlignmentX(Component.LEFT_ALIGNMENT);
			words.add(title);
			words.add(sub);
			JPanel wordsWrap = new JPanel(new java.awt.GridBagLayout());
			wordsWrap.setOpaque(false);
			java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
			gc.weightx = 1;
			gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
			wordsWrap.add(words, gc);
			top.add(wordsWrap, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new java.awt.GridBagLayout());
			buttons.setOpaque(false);
			buttons.add(copy);
			top.add(buttons, BorderLayout.EAST);
			add(top, BorderLayout.NORTH);

			after.setOpaque(false);
			after.setLayout(new BorderLayout(6, 0));
			after.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
			status.setFont(FontManager.getRunescapeSmallFont());
			status.setForeground(ChatComponents.MUTED);
			status.setLineWrap(true);
			status.setWrapStyleWord(true);
			status.setEditable(false);
			status.setFocusable(false);
			status.setOpaque(false);
			status.setBorder(null);
			after.add(status, BorderLayout.CENTER);
			open.setVisible(false);
			JPanel openWrap = new JPanel(new java.awt.GridBagLayout());
			openWrap.setOpaque(false);
			openWrap.add(open);
			after.add(openWrap, BorderLayout.EAST);
			add(after, BorderLayout.CENTER);
			if (!export.unresolved.isEmpty())
			{
				setStatus("Couldn't find: " + String.join(", ", export.unresolved) + ". Add them in " + export.target + " after importing.");
			}
			else
			{
				after.setVisible(false);
			}

			copy.addActionListener(e -> copy());
			open.addActionListener(e ->
			{
				if (openSetup != null && importedAs != null)
				{
					openSetup.accept(importedAs);
				}
			});
		}

		private void copy()
		{
			try
			{
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(export.text), null);
			}
			catch (IllegalStateException e)
			{
				setStatus("The clipboard is busy; try again.");
				return;
			}
			copied = true;
			copy.setText("Copied");
			if (importedAs == null)
			{
				setStatus("Copied. " + export.howTo);
			}
		}

		boolean awaitingImport()
		{
			return copied && importedAs == null && "inventory_setup".equals(export.kind);
		}

		void imported(String name)
		{
			importedAs = name;
			setStatus("Imported into Inventory Setups as \"" + name + "\".");
			open.setVisible(openSetup != null);
			relayout();
		}

		/** The chat list sizes rows itself, so ask it (not just this card) to lay out again. */
		private void relayout()
		{
			Component list = SwingUtilities.getAncestorOfClass(ChatComponents.MessageList.class, this);
			(list != null ? (JComponent) list : this).revalidate();
			repaint();
		}

		private void setStatus(String text)
		{
			status.setText(text);
			after.setVisible(true);
			relayout();
		}
	}

	/** A small square with the item (or, for ground markers, a marked tile). */
	private static final class IconWell extends JComponent
	{
		private final Export export;
		private final int itemId;

		IconWell(Export export)
		{
			this.export = export;
			this.itemId = export.iconItemId();
			setPreferredSize(new Dimension(36, 36));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ChatComponents.BASE_BG);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			if ("ground_markers".equals(export.kind))
			{
				int cx = getWidth() / 2, cy = getHeight() / 2;
				Polygon tile = new Polygon(new int[]{cx, cx + 12, cx, cx - 12}, new int[]{cy - 7, cy, cy + 7, cy}, 4);
				Color c = export.markerColor();
				g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
				g2.fill(tile);
				g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue()));
				g2.setStroke(new BasicStroke(1.5f));
				g2.draw(tile);
			}
			else
			{
				BufferedImage img = Crest.itemImage(itemId, this);
				if (img != null)
				{
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					g2.drawImage(img, (getWidth() - img.getWidth()) / 2, (getHeight() - img.getHeight()) / 2, null);
				}
				else
				{
					// No item (or icons unavailable): the target plugin's initial
					g2.setFont(FontManager.getRunescapeBoldFont());
					g2.setColor(ChatComponents.MUTED);
					String s = export.target.isEmpty() ? "?" : export.target.substring(0, 1);
					FontMetrics fm = g2.getFontMetrics();
					g2.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
				}
			}
			g2.dispose();
		}
	}

	/** A compact rounded button in the panel's style. */
	static final class PillButton extends javax.swing.JButton
	{
		private boolean hover;

		PillButton(String text)
		{
			super(text);
			setFont(FontManager.getRunescapeFont());
			setForeground(Color.WHITE);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
			});
		}

		@Override
		public Dimension getPreferredSize()
		{
			FontMetrics fm = getFontMetrics(getFont());
			return new Dimension(fm.stringWidth(getText()) + 20, 24);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(hover ? ChatComponents.HOVER_BG : ChatComponents.USER_BG);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			g2.setFont(getFont());
			g2.setColor(getForeground());
			FontMetrics fm = g2.getFontMetrics();
			g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
			g2.dispose();
		}
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private ExportCards()
	{
	}
}
