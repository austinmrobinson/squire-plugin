package com.squire;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough Markdown for chat replies in a Swing HTML pane: paragraphs, bullets, numbered
 * lists, bold/italic, inline and fenced code. Headings render bold; tables render monospace.
 */
final class MarkdownLite
{
	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])");
	private static final Pattern CODE = Pattern.compile("`([^`]+)`");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://(?:[^()\\s]|\\([^()\\s]*\\))+)\\)");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");

	// RuneLite's RuneScape font lacks many symbols; swap common ones for ASCII look-alikes
	private static final String[][] SUBSTITUTIONS = {
		{"\u2013", "-"}, {"\u2192", "->"}, {"\u2190", "<-"}, {"\u21D2", "=>"},
		{"\u2018", "'"}, {"\u2019", "'"}, {"\u201C", "\""}, {"\u201D", "\""},
		{"\u2265", ">="}, {"\u2264", "<="}, {"\u2260", "!="}, {"\u2248", "~"},
		{"\u2713", "+"}, {"\u2714", "+"}, {"\u2705", "+"}, {"\u2611", "+"},
		{"\u2717", "x"}, {"\u2718", "x"}, {"\u274C", "x"}, {"\u2716", "x"},
		{"\u2605", "*"}, {"\u2606", "*"}, {"\u2022", "-"}, {"\u00A0", " "},
	};

	private static final java.awt.Font FONT = net.runelite.client.ui.FontManager.getRunescapeFont();

	/** Replace or drop characters the RuneScape font can't draw, so they don't show as boxes. */
	static String normalize(String s)
	{
		for (String[] sub : SUBSTITUTIONS)
		{
			s = s.replace(sub[0], sub[1]);
		}
		StringBuilder out = new StringBuilder(s.length());
		s.codePoints().forEach(cp ->
		{
			if (cp == '\n' || cp == '\t' || FONT == null || FONT.canDisplay(cp))
			{
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	static String escape(String s)
	{
		return normalize(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Numbers a cited link: (url, link text) -> source number, or 0 for none. */
	interface Citer
	{
		int cite(String url, String text);
	}

	static String toHtml(String markdown)
	{
		return toHtml(markdown, null);
	}

	/** Links become clickable; with a citer, each is followed by its source number, e.g. "Vorkath [2]". */
	static String toHtml(String markdown, Citer citer)
	{
		markdown = normalize(markdown);
		StringBuilder out = new StringBuilder();
		boolean inList = false;
		boolean ordered = false;
		boolean inCode = false;
		int codeLines = 0;
		java.util.List<String> table = new java.util.ArrayList<>();
		boolean paragraphOpen = false;

		for (String raw : markdown.split("\n", -1))
		{
			if (raw.trim().startsWith("```"))
			{
				if (paragraphOpen)
				{
					out.append("</div>");
					paragraphOpen = false;
				}
				if (inList)
				{
					out.append(ordered ? "</ol>" : "</ul>");
					inList = false;
				}
				flushTable(out, table);
				if (!inCode)
				{
					// A dark inset box in the code colour; the RuneScape font has no monospace, so keep it
					out.append("<div style='background-color:#1a1a1a; margin-top:4px; padding:4px 6px'><font color='#f0c987'>");
					codeLines = 0;
				}
				else
				{
					out.append("</font></div>");
				}
				inCode = !inCode;
				continue;
			}
			if (inCode)
			{
				String line = escape(raw);
				int lead = line.length() - line.stripLeading().length();
				out.append(codeLines++ > 0 ? "<br>" : "").append("&nbsp;".repeat(lead)).append(line.stripLeading().replace("  ", " &nbsp;"));
				continue;
			}
			if (raw.trim().startsWith("|"))
			{
				if (paragraphOpen)
				{
					out.append("</div>");
					paragraphOpen = false;
				}
				if (!raw.matches("^\\s*\\|[\\s:|-]+\\|\\s*$"))
				{
					table.add(raw);
				}
				continue;
			}
			flushTable(out, table);

			Matcher bullet = BULLET.matcher(raw);
			Matcher numbered = NUMBERED.matcher(raw);
			if (bullet.matches() || numbered.matches())
			{
				if (paragraphOpen)
				{
					out.append("</div>");
					paragraphOpen = false;
				}
				boolean wantOrdered = !bullet.matches();
				if (inList && ordered != wantOrdered)
				{
					out.append(ordered ? "</ol>" : "</ul>");
					inList = false;
				}
				if (!inList)
				{
					out.append(wantOrdered ? "<ol>" : "<ul>");
					inList = true;
					ordered = wantOrdered;
				}
				String item = bullet.matches() ? bullet.group(1) : numbered.group(2);
				out.append("<li>").append(inline(item, citer)).append("</li>");
				continue;
			}
			if (inList)
			{
				out.append(ordered ? "</ol>" : "</ul>");
				inList = false;
			}

			if (raw.isBlank())
			{
				if (paragraphOpen)
				{
					out.append("</div>");
					paragraphOpen = false;
				}
				continue;
			}

			Matcher heading = HEADING.matcher(raw);
			String line = heading.matches() ? "<b>" + inline(heading.group(1), citer) + "</b>" : inline(raw, citer);
			if (!paragraphOpen)
			{
				out.append("<div style='margin-top:4px'>");
				paragraphOpen = true;
			}
			else
			{
				out.append("<br>");
			}
			out.append(line);
		}
		if (inCode)
		{
			out.append("</font></div>");
		}
		flushTable(out, table);
		if (inList)
		{
			out.append(ordered ? "</ol>" : "</ul>");
		}
		if (paragraphOpen)
		{
			out.append("</div>");
		}
		return out.toString();
	}

	/** Markdown table rows ("| a | b |") as an HTML table: the first row bold, the rest in the body colour. */
	private static void flushTable(StringBuilder out, java.util.List<String> rows)
	{
		if (rows.isEmpty())
		{
			return;
		}
		out.append("<table cellspacing='0' cellpadding='2' style='margin-top:4px'>");
		for (int r = 0; r < rows.size(); r++)
		{
			String row = rows.get(r).trim();
			row = row.replaceAll("^\\|", "").replaceAll("\\|$", "");
			out.append("<tr>");
			for (String cell : row.split("\\|"))
			{
				String html = inline(cell.trim(), null);
				out.append("<td style='padding-right:12px'>").append(r == 0 ? "<b>" + html + "</b>" : html).append("</td>");
			}
			out.append("</tr>");
		}
		out.append("</table>");
		rows.clear();
	}

	/** [[Page]] or [[Page|shown text]]: a link to that OSRS Wiki page (shown with a hover card in chat). */
	private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]");

	/** Turn [[Page]] wiki links into ordinary markdown links to the page. */
	private static String wikiLinks(String text)
	{
		Matcher m = WIKILINK.matcher(text);
		StringBuffer out = new StringBuffer();
		while (m.find())
		{
			String page = m.group(1).trim();
			String shown = m.group(2) == null ? page : m.group(2).trim();
			String url = WikiCards.WIKI + page.replace(' ', '_').replace("(", "%28").replace(")", "%29").replace("'", "%27").replace("?", "%3F");
			m.appendReplacement(out, Matcher.quoteReplacement("[" + shown + "](" + url + ")"));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String inline(String text, Citer citer)
	{
		String s = escape(wikiLinks(text));
		Matcher link = LINK.matcher(s);
		StringBuffer linked = new StringBuffer();
		while (link.find())
		{
			String label = link.group(1);
			String url = link.group(2).replace("&amp;", "&");
			int n = citer == null ? 0 : citer.cite(url, label.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"));
			String html = "<a href='" + url.replace("'", "%27").replace("&", "&amp;") + "'>" + label + "</a>"
				+ (n > 0 ? "<font color='#9a9a9a'>&nbsp;[" + n + "]</font>" : "");
			link.appendReplacement(linked, Matcher.quoteReplacement(html));
		}
		link.appendTail(linked);
		s = linked.toString();
		// Highlight rather than switch to a monospace font, which clashes with the RuneScape font
		s = CODE.matcher(s).replaceAll("<font color='#f0c987'>$1</font>");
		s = BOLD.matcher(s).replaceAll("<b>$1</b>");
		s = ITALIC.matcher(s).replaceAll("<i>$1</i>");
		return s;
	}

	private MarkdownLite()
	{
	}
}
