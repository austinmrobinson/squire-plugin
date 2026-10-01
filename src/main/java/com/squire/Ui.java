package com.squire;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;

/** Shared building blocks and formatting for the Home, Progress and Activity pages. */
final class Ui
{
	/** Activity colours, in rank order; "Other" is always grey. */
	private static final Color[] PALETTE = {
		ChatComponents.ACCENT, new Color(0xE0922F), new Color(0x3FA33F), new Color(0xC9483F), new Color(0xB064C8),
	};
	static final Color OTHER = Tokens.COLOR_SERIES_OTHER;

	static Color activityColor(int rank, String name)
	{
		return "Other".equals(name) ? OTHER : PALETTE[rank % PALETTE.length];
	}

	// ---- Building blocks

	static ChatComponents.Surface card()
	{
		ChatComponents.Surface c = new ChatComponents.Surface(ChatComponents.PANEL_BG, 4, true);
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		return c;
	}

	/** Label on the left (truncates), value on the right. */
	static JComponent row(JLabel left, JComponent right)
	{
		JPanel r = new JPanel(new BorderLayout(8, 0));
		r.setOpaque(false);
		r.add(left, BorderLayout.CENTER);
		if (right != null)
		{
			r.add(right, BorderLayout.EAST);
		}
		r.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
		return r;
	}

	static JLabel text(String s, Color color)
	{
		JLabel l = new JLabel(s);
		l.setFont(FontManager.getRunescapeFont());
		l.setForeground(color);
		return l;
	}

	static JLabel bold(String s)
	{
		JLabel l = new JLabel(s);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		return l;
	}

	static JLabel small(String s)
	{
		JLabel l = new JLabel(s);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ChatComponents.MUTED);
		return l;
	}

	static JLabel sectionLabel(String s)
	{
		JLabel l = small(s);
		l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
		return l;
	}




	/** Starts a new chat with a question; set by the sidebar. */
	static java.util.function.Consumer<String> askSquire = q -> {};

	/**
	 * A card with a small chat-plus button in its top-right corner that asks Squire a question about it (the
	 * question shows as the button's tooltip). The card keeps its own size and layout underneath.
	 */
	static JComponent withAsk(JComponent card, String question)
	{
		javax.swing.JButton ask = ChatComponents.iconButton("chat-plus", "Ask Squire: " + question);
		ask.addActionListener(e -> askSquire.accept(question));
		JPanel wrap = new JPanel(null)
		{
			@Override
			public Dimension getPreferredSize()
			{
				return card.getPreferredSize();
			}

			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height);
			}

			@Override
			public void doLayout()
			{
				card.setBounds(0, 0, getWidth(), getHeight());
				Dimension b = ask.getPreferredSize();
				ask.setBounds(getWidth() - b.width - 6, 5, b.width, b.height);
			}
		};
		wrap.setOpaque(false);
		wrap.add(ask);
		wrap.add(card);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		return wrap;
	}

	// ---- JSON and formatting

	static JsonObject obj(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	static String str(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	static double num(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
	}

	static String fmt(double v)
	{
		return String.format(Locale.US, "%,d", (long) v);
	}

	static String shortNumber(double v)
	{
		if (v >= 1e9)
		{
			return String.format(Locale.US, "%.2fB", v / 1e9);
		}
		if (v >= 1e6)
		{
			return String.format(Locale.US, "%.1fM", v / 1e6);
		}
		if (v >= 1e3)
		{
			return String.format(Locale.US, "%.0fK", v / 1e3);
		}
		return fmt(v);
	}

	static String oneDecimal(double v)
	{
		return String.format(Locale.US, "%.1f", v);
	}

	static String title(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** "5m", "3h", "2d" since an ISO timestamp. */
	static String ago(String iso)
	{
		if (iso == null || iso.isEmpty())
		{
			return "never";
		}
		Instant then;
		try
		{
			then = OffsetDateTime.parse(iso).toInstant();
		}
		catch (RuntimeException e)
		{
			return "";
		}
		long s = Math.max(0, Duration.between(then, Instant.now()).getSeconds());
		if (s < 60)
		{
			return s + "s";
		}
		if (s < 3600)
		{
			return s / 60 + "m";
		}
		if (s < 86400)
		{
			return s / 3600 + "h";
		}
		return s / 86400 + "d";
	}

	/** A small rounded colour square for legends. */
	static Icon swatch(Color color)
	{
		return new Icon()
		{
			@Override
			public void paintIcon(Component comp, Graphics g, int x, int y)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				Pixel.fill(g2, x, y, 8, 8, 1.5);
				g2.dispose();
			}

			@Override
			public int getIconWidth()
			{
				return 8;
			}

			@Override
			public int getIconHeight()
			{
				return 8;
			}
		};
	}

	/** "2h 22m", "45m", "0m". */
	static String duration(double minutes)
	{
		long m = Math.round(minutes);
		return m >= 60 ? m / 60 + "h " + m % 60 + "m" : m + "m";
	}

	static ImageIcon skillIcon(Function<Skill, BufferedImage> icons, String name)
	{
		try
		{
			return skillIcon(icons, Skill.valueOf(name.toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	static ImageIcon skillIcon(Function<Skill, BufferedImage> icons, Skill skill)
	{
		BufferedImage img = icons == null ? null : icons.apply(skill);
		return img == null ? null : new ImageIcon(img);
	}

	/** Make a component (and its non-interactive children) open something when clicked, with a hover highlight. */
	static void clickable(ChatComponents.Surface target, Runnable action)
	{
		target.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		java.awt.Color rest = target.fill();
		MouseAdapter m = new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
				{
					return;
				}
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				target.setFill(ChatComponents.HOVER_BG);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				// Moving onto a child also fires exit; only reset when the pointer really left the card
				if (!target.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), target)))
				{
					target.setFill(rest);
				}
			}
		};
		addDeep(target, m);
	}

	private static void addDeep(Component c, MouseAdapter m)
	{
		if (c instanceof javax.swing.text.JTextComponent || c instanceof javax.swing.AbstractButton)
		{
			return;
		}
		c.addMouseListener(m);
		if (c instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) c).getComponents())
			{
				addDeep(child, m);
			}
		}
	}

	private Ui()
	{
	}
}
