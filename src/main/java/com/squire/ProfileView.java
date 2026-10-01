package com.squire;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.MessageList;
import com.squire.ChatComponents.Surface;
import com.squire.WelcomeView.Wrapped;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * Profile: the avatar card (character, background, frame, emblem), sharing the public profile, and choosing what it
 * shows: gear the character owns, and items to highlight. Every change saves straight away and the server checks it.
 * Changing gear re-renders the character here (see AvatarStudio). The same editor is on the website.
 */
class ProfileView extends JPanel
{
	interface Controller
	{
		void load(Consumer<AccountApi.Result> callback);

		void edit(JsonObject patch, Consumer<AccountApi.Result> callback);

		/** The character's own items for an equipment slot, or (slot < 0) anything for highlights. */
		void items(int slot, String query, Consumer<AccountApi.Result> callback);

		/** Open the editor in the browser (a one-time link). */
		void openWeb();

		/** Render the character again now (after a gear change) and upload it. */
		void renderAvatar(JsonObject profile);

		/** The latest render of the character, or null. */
		BufferedImage character();

		/** An item's icon (may load later; null without one). */
		BufferedImage icon(int itemId);
	}

	private static final int CARD = 176, SWATCH = 44;
	private static final Color SELECTED = new Color(0x7A86FF);

	private final Controller controller;
	private final MessageList list = new MessageList(null, 0);
	private JsonObject data;
	private String status = "";
	private boolean statusError;

	ProfileView(Controller controller)
	{
		super(new BorderLayout());
		this.controller = controller;
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(8, 1, 16, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);
		render();
	}

	void onShown()
	{
		status = data == null ? "Loading..." : "";
		render();
		controller.load(this::loaded);
	}

	/** For previews: show this profile without fetching. */
	void show(JsonObject profile)
	{
		data = profile;
		render();
	}

	/** A new render of the character arrived. */
	void characterUpdated()
	{
		if (data != null)
		{
			status = "";
		}
		render();
	}

	private void loaded(AccountApi.Result r)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (r.json != null && r.json.has("options"))
			{
				boolean first = data == null;
				data = r.json;
				status = "";
				statusError = false;
				if (!data.get("active").getAsBoolean())
				{
					// First visit: create the profile (private) so the avatar can be rendered for it
					edit(new JsonObject());
				}
				else if (first && data.get("needsRender").getAsBoolean())
				{
					controller.renderAvatar(data);
					status = "Drawing your character...";
				}
			}
			else
			{
				status = r.error != null ? r.error : "Couldn't load your profile.";
				statusError = true;
			}
			render();
		});
	}

	private void edit(JsonObject patch)
	{
		status = "Saving...";
		statusError = false;
		render();
		controller.edit(patch, r -> SwingUtilities.invokeLater(() ->
		{
			if (r.json != null && r.json.has("options"))
			{
				data = r.json;
				status = "";
				if (data.get("needsRender").getAsBoolean())
				{
					controller.renderAvatar(data);
					status = "Drawing your character...";
				}
			}
			else
			{
				status = r.error != null ? r.error : "Couldn't save that.";
				statusError = true;
			}
			render();
		}));
	}

	private void render()
	{
		list.removeAll();
		list.add(ChatComponents.place(new Preview(), Align.FILL, 0));
		if (!status.isEmpty())
		{
			Wrapped s = new Wrapped(status, statusError ? new Color(0xFF8A80) : ChatComponents.MUTED, true);
			list.add(ChatComponents.place(s, Align.FILL, 6));
		}
		if (data != null)
		{
			list.add(ChatComponents.place(shareCard(), Align.FILL, 12));
			list.add(ChatComponents.place(title("Background"), Align.LEFT, 14));
			list.add(ChatComponents.place(backgrounds(), Align.FILL, 6));
			list.add(ChatComponents.place(title("Frame"), Align.LEFT, 14));
			list.add(ChatComponents.place(frames("squire", "rank"), Align.FILL, 6));
			list.add(ChatComponents.place(subtitle("Accomplishments"), Align.LEFT, 8));
			list.add(ChatComponents.place(frames("accomplishment"), Align.FILL, 6));
			list.add(ChatComponents.place(title("Emblem"), Align.LEFT, 14));
			list.add(ChatComponents.place(emblems(), Align.FILL, 6));
			list.add(ChatComponents.place(title("Gear"), Align.LEFT, 14));
			list.add(ChatComponents.place(note("Dress your character in gear you own. Slots you leave alone show what you're wearing."), Align.FILL, 4));
			list.add(ChatComponents.place(gearCard(), Align.FILL, 6));
			list.add(ChatComponents.place(title("Highlights"), Align.LEFT, 14));
			list.add(ChatComponents.place(note("Up to " + data.getAsJsonObject("options").get("maxHighlights").getAsInt()
				+ " items to show off: gear, pets, rare drops."), Align.FILL, 4));
			list.add(ChatComponents.place(highlights(), Align.FILL, 6));
		}
		list.revalidate();
		list.repaint();
	}

	// ---- Sections

	private JComponent shareCard()
	{
		Surface card = HomeView.listCard();
		boolean shared = data.get("public").getAsBoolean();
		JComponent toggle = HomeView.listRow(null, "Share my profile", new ChatComponents.Switch(shared), () ->
		{
			JsonObject p = new JsonObject();
			p.addProperty("public", !shared);
			edit(p);
		});
		toggle.setToolTipText(shared ? "Anyone with the link can see it" : "Off: nobody can see it");
		card.add(toggle);
		String url = Ui.str(data, "url");
		if (shared && !url.isEmpty())
		{
			card.add(HomeView.divider());
			JComponent copy = HomeView.listRow(null, "Copy link", Ui.small(url.replaceFirst("^https?://", "")), () ->
			{
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(url), null);
				status = "Link copied.";
				statusError = false;
				render();
			});
			copy.setToolTipText(url);
			card.add(copy);
			card.add(HomeView.divider());
			card.add(HomeView.listRow(null, "Open my profile", new JLabel(SvgIcon.load("chevron-right", 16, null)),
				() -> net.runelite.client.util.LinkBrowser.browse(url)));
		}
		card.add(HomeView.divider());
		card.add(HomeView.listRow(null, "Edit on the web", new JLabel(SvgIcon.load("chevron-right", 16, null)), controller::openWeb));
		return card;
	}

	private JComponent backgrounds()
	{
		JPanel grid = grid(5);
		// The frame without its badge: at this size the badge would cover the background being chosen
		JsonObject frame = selected("frames", "frame").deepCopy();
		frame.remove("itemId");
		for (JsonElement e : options("backgrounds"))
		{
			JsonObject bg = e.getAsJsonObject();
			String id = Ui.str(bg, "id");
			grid.add(new Swatch(Ui.str(bg, "name"), id.equals(Ui.str(data, "background")), true, g -> AvatarCard.paint(g, 0, 0, SWATCH, bg, frame, null, null, null), () ->
			{
				JsonObject p = new JsonObject();
				p.addProperty("background", id);
				edit(p);
			}));
		}
		return grid;
	}

	private JComponent frames(String... groups)
	{
		JPanel grid = grid(5);
		JsonObject bg = selected("backgrounds", "background");
		for (JsonElement e : options("frames"))
		{
			JsonObject f = e.getAsJsonObject();
			if (!java.util.Arrays.asList(groups).contains(Ui.str(f, "group")))
			{
				continue;
			}
			String id = Ui.str(f, "id");
			boolean unlocked = f.get("unlocked").getAsBoolean();
			Swatch s = new Swatch(Ui.str(f, "name") + (unlocked ? "" : ": " + Ui.str(f, "requirement")), id.equals(Ui.str(data, "frame")), unlocked,
				g -> AvatarCard.paint(g, 0, 0, SWATCH, bg, f, null, null, controller::icon), () ->
				{
					JsonObject p = new JsonObject();
					p.addProperty("frame", id);
					edit(p);
				});
			grid.add(s);
		}
		return grid;
	}

	private JComponent emblems()
	{
		JPanel grid = grid(5);
		for (JsonElement e : options("emblems"))
		{
			JsonObject em = e.getAsJsonObject();
			if (!em.get("unlocked").getAsBoolean())
			{
				continue;
			}
			String id = Ui.str(em, "id");
			BufferedImage img = AvatarCard.emblem(id);
			grid.add(new Swatch(Ui.str(em, "name"), id.equals(Ui.str(data, "emblem")), true, g ->
			{
				g.setColor(ChatComponents.BASE_BG);
				Pixel.fill(g, 0, 0, SWATCH, SWATCH, 4);
				g.setColor(ChatComponents.BORDER);
				Pixel.draw(g, 0, 0, SWATCH, SWATCH, 4);
				if (img != null)
				{
					int s = Math.max(1, 26 / Math.max(img.getWidth(), img.getHeight()));
					int w = img.getWidth() * s, h = img.getHeight() * s;
					g.drawImage(img, (SWATCH - w) / 2, (SWATCH - h) / 2, w, h, null);
				}
			}, () ->
			{
				JsonObject p = new JsonObject();
				p.addProperty("emblem", id);
				edit(p);
			}));
		}
		return grid;
	}

	private JComponent gearCard()
	{
		Surface card = HomeView.listCard();
		JsonObject chosen = data.has("gearItems") ? data.getAsJsonObject("gearItems") : new JsonObject();
		boolean first = true;
		for (JsonElement e : data.getAsJsonObject("options").getAsJsonArray("slots"))
		{
			JsonObject slot = e.getAsJsonObject();
			int index = slot.get("slot").getAsInt();
			JsonObject item = chosen.has(String.valueOf(index)) ? chosen.getAsJsonObject(String.valueOf(index)) : null;
			if (!first)
			{
				card.add(HomeView.divider());
			}
			first = false;
			String name = item == null ? "What I wear" : Ui.str(item, "name");
			JComponent row = HomeView.listRow(item == null ? null : iconOf(item.get("id").getAsInt()), Ui.str(slot, "name"), Ui.small(name), () ->
				pick(index, Ui.str(slot, "name"), picked ->
				{
					JsonObject gear = new JsonObject();
					if (picked == null)
					{
						gear.add(String.valueOf(index), com.google.gson.JsonNull.INSTANCE);
					}
					else
					{
						gear.addProperty(String.valueOf(index), picked.get("id").getAsInt());
					}
					JsonObject p = new JsonObject();
					p.add("gear", gear);
					edit(p);
				}));
			card.add(row);
		}
		return card;
	}

	private JComponent highlights()
	{
		JPanel grid = grid(5);
		JsonArray chosen = data.getAsJsonArray("highlights");
		List<Integer> ids = new ArrayList<>();
		chosen.forEach(h -> ids.add(h.getAsJsonObject().get("id").getAsInt()));
		for (JsonElement e : chosen)
		{
			JsonObject h = e.getAsJsonObject();
			int id = h.get("id").getAsInt();
			grid.add(new Swatch(Ui.str(h, "name") + " (click to remove)", false, true, g -> slot(g, controller.icon(id)), () ->
			{
				List<Integer> rest = new ArrayList<>(ids);
				rest.remove(Integer.valueOf(id));
				edit(highlightsPatch(rest));
			}));
		}
		int max = data.getAsJsonObject("options").get("maxHighlights").getAsInt();
		if (ids.size() < max)
		{
			ImageIcon plus = SvgIcon.load("plus", 16, null);
			grid.add(new Swatch("Add a highlight", false, true, g ->
			{
				slot(g, null);
				plus.paintIcon(null, g, (SWATCH - 16) / 2, (SWATCH - 16) / 2);
			}, () -> pick(-1, "Highlight", picked ->
			{
				if (picked != null)
				{
					List<Integer> more = new ArrayList<>(ids);
					more.add(picked.get("id").getAsInt());
					edit(highlightsPatch(more));
				}
			})));
		}
		return grid;
	}

	private static JsonObject highlightsPatch(List<Integer> ids)
	{
		JsonObject p = new JsonObject();
		JsonArray a = new JsonArray();
		ids.forEach(a::add);
		p.add("highlights", a);
		return p;
	}

	// ---- Picker: search the character's own items

	/** Choose an item for a slot (slot >= 0, with "What I wear") or a highlight; {@code onPick} gets null for "What I wear". */
	private void pick(int slot, String what, Consumer<JsonObject> onPick)
	{
		Window owner = SwingUtilities.getWindowAncestor(this);
		JDialog dialog = new JDialog(owner, slot >= 0 ? "Choose your " + what.toLowerCase() : "Add a highlight", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		JTextField search = new JTextField();
		search.setFont(FontManager.getRunescapeFont());
		DefaultListModel<JsonObject> model = new DefaultListModel<>();
		JList<JsonObject> results = new JList<>(model);
		results.setCellRenderer(new ItemRenderer());
		results.setVisibleRowCount(10);
		JsonObject wear = new JsonObject();
		wear.addProperty("id", -1);
		wear.addProperty("name", "What I wear");
		Runnable load = () -> controller.items(slot, search.getText(), r -> SwingUtilities.invokeLater(() ->
		{
			model.clear();
			if (slot >= 0)
			{
				model.addElement(wear);
			}
			if (r.json != null && r.json.has("items"))
			{
				r.json.getAsJsonArray("items").forEach(i -> model.addElement(i.getAsJsonObject()));
			}
			if (model.isEmpty())
			{
				JsonObject none = new JsonObject();
				none.addProperty("id", -2);
				none.addProperty("name", "Nothing found. Open your bank so Squire knows what you own.");
				model.addElement(none);
			}
		}));
		Timer debounce = new Timer(200, e -> load.run());
		debounce.setRepeats(false);
		search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				debounce.restart();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				debounce.restart();
			}
		});
		Runnable choose = () ->
		{
			JsonObject v = results.getSelectedValue();
			if (v == null || v.get("id").getAsInt() == -2)
			{
				return;
			}
			dialog.dispose();
			onPick.accept(v.get("id").getAsInt() == -1 ? null : v);
		};
		results.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getClickCount() >= 1)
				{
					choose.run();
				}
			}
		});
		search.addActionListener(e ->
		{
			if (results.getSelectedIndex() < 0 && !model.isEmpty())
			{
				results.setSelectedIndex(0);
			}
			choose.run();
		});
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		content.setBackground(ChatComponents.BASE_BG);
		content.add(search, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(results);
		scroll.setPreferredSize(new Dimension(280, 360));
		content.add(scroll);
		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(this);
		load.run();
		dialog.setVisible(true);
	}

	private final class ItemRenderer extends JLabel implements ListCellRenderer<JsonObject>
	{
		ItemRenderer()
		{
			setOpaque(true);
			setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			setFont(FontManager.getRunescapeFont());
			setIconTextGap(8);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends JsonObject> l, JsonObject value, int index, boolean selected, boolean focus)
		{
			int id = value.get("id").getAsInt();
			setText(Ui.str(value, "name") + (value.has("collectionLog") && value.get("collectionLog").getAsBoolean() ? "  (log)" : ""));
			setIcon(id >= 0 ? iconOf(id) : null);
			setBackground(selected ? ChatComponents.HOVER_BG : ChatComponents.BASE_BG);
			setForeground(id == -2 ? ChatComponents.MUTED : Color.WHITE);
			return this;
		}
	}

	// ---- Pieces

	private JsonArray options(String kind)
	{
		return data.getAsJsonObject("options").getAsJsonArray(kind);
	}

	/** The chosen background or frame's option object. */
	private JsonObject selected(String kind, String key)
	{
		String id = Ui.str(data, key);
		for (JsonElement e : options(kind))
		{
			if (Ui.str(e.getAsJsonObject(), "id").equals(id))
			{
				return e.getAsJsonObject();
			}
		}
		return options(kind).get(0).getAsJsonObject();
	}

	private javax.swing.Icon iconOf(int itemId)
	{
		BufferedImage img = controller.icon(itemId);
		if (img instanceof net.runelite.client.util.AsyncBufferedImage)
		{
			((net.runelite.client.util.AsyncBufferedImage) img).onLoaded(() -> SwingUtilities.invokeLater(list::repaint));
		}
		return img == null ? null : new ImageIcon(img);
	}

	private void slot(Graphics2D g, BufferedImage icon)
	{
		g.setColor(ChatComponents.BASE_BG);
		Pixel.fill(g, 0, 0, SWATCH, SWATCH, 4);
		g.setColor(ChatComponents.BORDER);
		Pixel.draw(g, 0, 0, SWATCH, SWATCH, 4);
		Pixel.bevel(g, 1, 1, SWATCH - 2, SWATCH - 2, 3, ChatComponents.CARD_DARK, ChatComponents.CARD_LIGHT);
		if (icon != null)
		{
			if (icon instanceof net.runelite.client.util.AsyncBufferedImage)
			{
				((net.runelite.client.util.AsyncBufferedImage) icon).onLoaded(() -> SwingUtilities.invokeLater(list::repaint));
			}
			g.drawImage(icon, (SWATCH - icon.getWidth()) / 2, (SWATCH - icon.getHeight()) / 2, null);
		}
	}

	private static JPanel grid(int columns)
	{
		JPanel grid = new JPanel(new GridLayout(0, columns, 6, 6));
		grid.setOpaque(false);
		grid.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		return grid;
	}

	private static JLabel title(String text)
	{
		JLabel l = Ui.bold(text);
		l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
		return l;
	}

	private static JLabel subtitle(String text)
	{
		JLabel l = Ui.small(text);
		l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
		return l;
	}

	private static Wrapped note(String text)
	{
		Wrapped w = new Wrapped(text, ChatComponents.MUTED, false);
		w.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
		return w;
	}

	/** The avatar card, centred, as it will look on the profile. */
	private final class Preview extends JComponent implements ChatComponents.HeightForWidth
	{
		@Override
		public int heightForWidth(int width)
		{
			return CARD + 8;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(CARD, CARD + 8);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (data == null)
			{
				return;
			}
			AvatarCard.paint((Graphics2D) g, (getWidth() - CARD) / 2, 4, CARD, selected("backgrounds", "background"), selected("frames", "frame"),
				Ui.str(data, "emblem"), controller.character(), controller::icon);
		}
	}

	/** A small square choice: drawn by {@code painter}, outlined when selected, dimmed and unclickable when locked. */
	private final class Swatch extends JComponent
	{
		private final boolean selected, enabled;
		private final Consumer<Graphics2D> painter;

		Swatch(String tooltip, boolean selected, boolean enabled, Consumer<Graphics2D> painter, Runnable onClick)
		{
			this.selected = selected;
			this.enabled = enabled;
			this.painter = painter;
			setToolTipText(tooltip);
			setPreferredSize(new Dimension(SWATCH, SWATCH + 4));
			if (enabled)
			{
				setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseReleased(MouseEvent e)
					{
						if (SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint()))
						{
							onClick.run();
						}
					}
				});
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			int x = (getWidth() - SWATCH) / 2, y = 2;
			g2.translate(x, y);
			if (!enabled)
			{
				g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.3f));
			}
			painter.accept(g2);
			g2.dispose();
			if (selected)
			{
				Graphics2D o = (Graphics2D) g.create();
				o.setColor(SELECTED);
				o.drawRect(x - 2, y - 2, SWATCH + 3, SWATCH + 3);
				o.drawRect(x - 1, y - 1, SWATCH + 1, SWATCH + 1);
				o.dispose();
			}
			if (!enabled)
			{
				javax.swing.ImageIcon lock = SvgIcon.load("lock", 12, Color.WHITE);
				if (lock != null)
				{
					lock.paintIcon(this, g, x + SWATCH - 14, y + SWATCH - 14);
				}
			}
		}
	}
}
