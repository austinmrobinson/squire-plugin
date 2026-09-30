package com.squire;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Something attached to a chat message: a screenshot/image, or text (pasted or from a .txt/.md/... file).
 * Images go to the model as file parts; text goes as a quoted text part with its file name.
 */
final class Attachment
{
	enum Kind
	{
		IMAGE, TEXT
	}

	static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
	static final Set<String> TEXT_TYPES = Set.of("txt", "md", "markdown", "json", "csv", "log", "yml", "yaml", "tsv");
	/** Pasted text longer than this becomes an attachment instead of going into the input. */
	static final int PASTE_AS_FILE_CHARS = 1000;
	static final int PASTE_AS_FILE_LINES = 12;
	private static final int MAX_TEXT_CHARS = 100_000;
	/** Long edge models see well; bigger images just cost more. */
	private static final int MAX_IMAGE_EDGE = 1568;
	/** Keeps a message with a few screenshots under the host's ~4.5MB request limit (base64 adds a third). */
	private static final int MAX_IMAGE_BYTES = 800_000;

	final Kind kind;
	final String name;
	final String mediaType;
	final byte[] bytes;
	final String text;
	final BufferedImage thumbnail;
	/** For context from a page (e.g. "Plan"): shown on the tile instead of a file type; null for files. */
	String label;

	private Attachment(Kind kind, String name, String mediaType, byte[] bytes, String text, BufferedImage thumbnail)
	{
		this.kind = kind;
		this.name = name;
		this.mediaType = mediaType;
		this.bytes = bytes;
		this.text = text;
		this.thumbnail = thumbnail;
	}

	static Attachment image(BufferedImage source, String name) throws IOException
	{
		BufferedImage img = fit(source, MAX_IMAGE_EDGE);
		byte[] png = encode(img, "png", 1f);
		String type = "image/png";
		String file = name == null ? "Screenshot.png" : name;
		if (png.length > MAX_IMAGE_BYTES)
		{
			// Photos and busy screenshots: JPEG, shrinking until it fits
			type = "image/jpeg";
			file = file.replaceAll("\\.[A-Za-z]+$", "") + ".jpg";
			int edge = MAX_IMAGE_EDGE;
			png = encode(toRgb(img), "jpg", 0.85f);
			while (png.length > MAX_IMAGE_BYTES && edge > 400)
			{
				edge = edge * 3 / 4;
				png = encode(toRgb(fit(source, edge)), "jpg", 0.8f);
			}
		}
		return new Attachment(Kind.IMAGE, file, type, png, null, fit(source, 144));
	}

	/**
	 * Context from the page the player asked from (their plan, their progress): sent to Squire like a text file,
	 * shown as a tile with a label and an icon instead of a file type.
	 */
	static Attachment context(String label, String name, String markdown, BufferedImage icon)
	{
		Attachment a = new Attachment(Kind.TEXT, name, "text/markdown", null, markdown, icon);
		a.label = label;
		return a;
	}

	static Attachment text(String text, String name)
	{
		String body = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) + "\n[truncated]" : text;
		boolean markdown = name != null ? name.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown)$") : looksLikeMarkdown(body);
		String file = name != null ? name : markdown ? "Pasted text.md" : "Pasted text.txt";
		return new Attachment(Kind.TEXT, file, markdown ? "text/markdown" : "text/plain", null, body, null);
	}

	/** An attachment for a file the player chose or dropped, or null if it's not a kind we take. */
	static Attachment fromFile(net.runelite.client.util.Filepath file) throws IOException
	{
		String name = file.getFileName();
		String ext = extensionOf(name);
		if (IMAGE_TYPES.contains(ext))
		{
			try (java.io.InputStream in = file.openInputStream())
			{
				BufferedImage img = ImageIO.read(in);
				return img == null ? null : image(img, name);
			}
		}
		if (TEXT_TYPES.contains(ext))
		{
			try (java.io.InputStream in = file.openInputStream())
			{
				return text(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
			}
		}
		return null;
	}

	/** For the chat history: the kind, name and a small thumbnail (not the full image or text). */
	com.google.gson.JsonObject toHistory()
	{
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("kind", kind.name());
		o.addProperty("name", name);
		o.addProperty("mediaType", mediaType);
		o.addProperty("size", sizeLabel());
		if (label != null)
		{
			o.addProperty("label", label);
		}
		if (thumbnail != null)
		{
			try
			{
				o.addProperty("thumbnail", Base64.getEncoder().encodeToString(encode(fit(thumbnail, 96), "png", 1f)));
			}
			catch (IOException ignored)
			{
				// no thumbnail
			}
		}
		return o;
	}

	/** A tile for a sent message restored from the history. */
	static Attachment fromHistory(com.google.gson.JsonObject o)
	{
		Kind kind = "IMAGE".equals(o.has("kind") ? o.get("kind").getAsString() : "") ? Kind.IMAGE : Kind.TEXT;
		BufferedImage thumb = null;
		if (o.has("thumbnail"))
		{
			try
			{
				thumb = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(o.get("thumbnail").getAsString())));
			}
			catch (IOException | IllegalArgumentException ignored)
			{
				// no thumbnail
			}
		}
		String name = o.has("name") ? o.get("name").getAsString() : "Attachment";
		String type = o.has("mediaType") ? o.get("mediaType").getAsString() : "";
		Attachment a = new Attachment(kind, name, type, new byte[0], kind == Kind.TEXT ? "" : null, thumb);
		a.label = o.has("label") ? o.get("label").getAsString() : null;
		return a;
	}

	/** True for pasted text that should become a file rather than go into the input. */
	static boolean isLongPaste(String s)
	{
		return s.length() > PASTE_AS_FILE_CHARS || s.split("\n", -1).length > PASTE_AS_FILE_LINES;
	}

	/** The label on a tile: MD, TXT, PNG, ... */
	String extension()
	{
		String ext = extensionOf(name);
		return ext.isEmpty() ? (kind == Kind.IMAGE ? "IMG" : "TXT") : ext.toUpperCase(Locale.ROOT);
	}

	String sizeLabel()
	{
		long n = kind == Kind.IMAGE ? bytes.length : text.getBytes(StandardCharsets.UTF_8).length;
		return n >= 1_000_000 ? String.format(Locale.US, "%.1f MB", n / 1e6) : n >= 1000 ? (n / 1000) + " KB" : n + " B";
	}

	/** This attachment as a message part for the agent (AI SDK UserContent). */
	Map<String, Object> toPart()
	{
		Map<String, Object> part = new LinkedHashMap<>();
		if (kind == Kind.IMAGE)
		{
			part.put("type", "file");
			part.put("data", "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes));
			part.put("mediaType", mediaType);
			part.put("filename", name);
		}
		else
		{
			part.put("type", "text");
			part.put("text", "Attached file: " + name + "\n\n" + text);
		}
		return part;
	}

	static boolean looksLikeMarkdown(String s)
	{
		return s.matches("(?s).*(^|\n)(#{1,6} |[-*] |\\d+\\. |> |```).*") || s.contains("**") || s.matches("(?s).*\\[[^\\]]+\\]\\([^)]+\\).*");
	}

	static String extensionOf(String name)
	{
		int dot = name == null ? -1 : name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static BufferedImage fit(BufferedImage img, int maxEdge)
	{
		double scale = Math.min(1.0, maxEdge / (double) Math.max(img.getWidth(), img.getHeight()));
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale)), h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static BufferedImage toRgb(BufferedImage img)
	{
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(java.awt.Color.BLACK);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	private static byte[] encode(BufferedImage img, String format, float quality) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (format.equals("jpg"))
		{
			ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(out))
			{
				writer.setOutput(ios);
				writer.write(null, new IIOImage(img, null, null), param);
			}
			finally
			{
				writer.dispose();
			}
		}
		else
		{
			ImageIO.write(img, format, out);
		}
		return out.toByteArray();
	}
}
