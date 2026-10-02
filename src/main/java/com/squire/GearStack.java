package com.squire;

import com.google.gson.JsonObject;
import com.squire.ChatComponents.HeightForWidth;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/**
 * Several gear setups in one reply (melee or ranged for the same boss, two different bosses), as a stack: one card
 * open, the others closed to a single row each with their weapon, name and DPS. Clicking a closed one opens it in
 * place of the open one, so a reply with three setups is one card tall, not three.
 */
final class GearStack extends JPanel implements HeightForWidth
{
	private final List<JsonObject> results;
	private final GearCard[] cards;
	private final PlanView.Stack body = new PlanView.Stack();
	private int open;

	GearStack(List<JsonObject> results)
	{
		this.results = results;
		this.cards = new GearCard[results.size()];
		setOpaque(false);
		setLayout(new BorderLayout());
		add(body);
		render();
	}

	@Override
	public int heightForWidth(int width)
	{
		return body.heightForWidth(width);
	}

	private void render()
	{
		body.removeAll();
		for (int i = 0; i < results.size(); i++)
		{
			if (i == open)
			{
				// Cards are kept once made, so a setup edited and then closed is still edited when reopened
				if (cards[i] == null)
				{
					cards[i] = new GearCard(results.get(i));
				}
				body.add(cards[i], i == 0 ? 0 : 4);
			}
			else
			{
				body.add(new Closed(i), i == 0 ? 0 : 4);
			}
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

	/** A closed card: the weapon, the setup's name, and its DPS and kill time on the right. Click to open it. */
	private final class Closed extends JComponent
	{
		private static final int H = 40;
		private final String[] summary;
		private boolean hover;

		Closed(int index)
		{
			this.summary = GearCard.summary(results.get(index));
			setPreferredSize(new Dimension(10, H));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText("Show " + summary[0]);
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
					open = index;
					render();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			int w = getWidth();
			// The same surface and edge as an open card, so it reads as a card lying closed
			g2.setColor(hover ? Tokens.COLOR_SURFACE_HOVER : ChatComponents.PANEL_BG);
			g2.fillRect(0, 0, w, H);
			g2.setColor(Tokens.COLOR_BORDER_EDGE);
			g2.drawRect(0, 0, w - 1, H - 1);
			int itemId = summary[2].isEmpty() ? -1 : Integer.parseInt(summary[2]);
			BufferedImage img = itemId > 0 ? Crest.itemImage(itemId, this) : null;
			if (img != null)
			{
				g2.drawImage(img, 6 + (32 - img.getWidth()) / 2, (H - img.getHeight()) / 2, null);
			}
			g2.setFont(FontManager.getRunescapeSmallFont());
			java.awt.FontMetrics small = g2.getFontMetrics();
			int right = w - 10 - small.stringWidth(summary[1]);
			g2.setColor(ChatComponents.MUTED);
			g2.drawString(summary[1], right, 25);
			g2.setFont(FontManager.getRunescapeBoldFont());
			g2.setColor(java.awt.Color.WHITE);
			String name = summary[0];
			java.awt.FontMetrics bold = g2.getFontMetrics();
			int room = right - 44 - 8;
			if (bold.stringWidth(name) > room)
			{
				while (name.length() > 1 && bold.stringWidth(name + "...") > room)
				{
					name = name.substring(0, name.length() - 1);
				}
				name = name + "...";
			}
			g2.drawString(name, 44, 25);
			g2.dispose();
		}
	}
}
