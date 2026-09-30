package com.squire;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import okhttp3.OkHttpClient;

/**
 * Sends from the floating composer in an offscreen (never shown) window and captures frames of the composer
 * settling into the chat: build/preview/10-transition-strip.png. ./gradlew transitionCheck
 */
public class TransitionCheck
{
	public static void main(String[] args) throws Exception
	{
		AtomicReference<JFrame> frameRef = new AtomicReference<>();
		AtomicReference<SquireSidebar> sidebarRef = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			RuneLiteLAF.setup();
			ModelCatalog models = new ModelCatalog(new OkHttpClient(), new Gson(), () -> "", () -> "");
			ChatSessions sessions = new ChatSessions(() -> new ChatView(new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:1", () -> "x", () -> ModelCatalog.DEFAULT_ID),
				Map::of, models, () -> ChatView.AUTO_MODEL, id -> {}), null);
			AccountApi api = new AccountApi(new OkHttpClient(), new Gson(), () -> "", () -> "", () -> null);
			SkillIconManager icons = new SkillIconManager();
			ProgressView progress = new ProgressView(skill -> icons.getSkillImage(skill, true), null);
			SquireSidebar sidebar = new SquireSidebar(actions -> new HomeView(api, progress, actions, sessions, java.util.List::of),
				progress, new ActivityView(api), sessions, new SettingsView(() -> {}), SquireSidebar.DEFAULT_WIDTH, w -> {});
			progress.show(new Gson().fromJson(PanelPreview.SAMPLE_OVERVIEW, com.google.gson.JsonObject.class));
			sidebar.showPage("progress");
			JFrame frame = new JFrame();
			frame.setUndecorated(true);
			frame.setContentPane(sidebar);
			frame.setSize(SquireSidebar.DEFAULT_WIDTH, 620);
			frame.addNotify();
			frame.validate();
			frameRef.set(frame);
			sidebarRef.set(sidebar);
			sidebar.floatingAsk().setDraft("Kill Vorkath faster");
			frame.validate();
		});
		List<BufferedImage> frames = new ArrayList<>();
		frames.add(capture(frameRef.get()));
		SwingUtilities.invokeAndWait(() -> sidebarRef.get().floatingAsk().submit());
		long start = System.currentTimeMillis();
		boolean ghostSeen = false;
		for (long at : new long[]{0, 50, 100, 170, 400})
		{
			Thread.sleep(Math.max(0, at - (System.currentTimeMillis() - start)));
			AtomicReference<Boolean> hasGhost = new AtomicReference<>(false);
			SwingUtilities.invokeAndWait(() ->
			{
				JLayeredPane layer = frameRef.get().getRootPane().getLayeredPane();
				hasGhost.set(layer.getComponentCountInLayer(JLayeredPane.DRAG_LAYER) > 0);
			});
			ghostSeen |= hasGhost.get();
			frames.add(capture(frameRef.get()));
			System.out.println("t=" + at + "ms: transition " + (hasGhost.get() ? "running" : "done"));
		}
		BufferedImage strip = new BufferedImage(frames.size() * (SquireSidebar.DEFAULT_WIDTH + 8), 620, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = strip.createGraphics();
		for (int i = 0; i < frames.size(); i++)
		{
			g.drawImage(frames.get(i), i * (SquireSidebar.DEFAULT_WIDTH + 8), 0, null);
		}
		g.dispose();
		new File("build/preview").mkdirs();
		ImageIO.write(strip, "png", new File("build/preview/10-transition-strip.png"));
		System.out.println(ghostSeen ? "PASS  transition ran and finished" : "FAIL  no transition seen");
		System.exit(0);
	}

	private static BufferedImage capture(JFrame frame) throws Exception
	{
		AtomicReference<BufferedImage> out = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			frame.validate();
			BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			frame.getRootPane().paint(g);
			g.dispose();
			out.set(img);
		});
		return out.get();
	}
}
