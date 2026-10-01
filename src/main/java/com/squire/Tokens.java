package com.squire;

import java.awt.Color;

/**
 * Design tokens, generated from design/tokens by server/scripts/tokens.ts (npm run tokens). Do not edit: change the
 * tokens and rebuild. The website gets the same values as CSS variables (server/app/tokens.css).
 */
final class Tokens
{
	/** RuneLite's sidebar and title bar; the panel background */
	static final Color COLOR_SURFACE_BASE = new Color(0x1E1E1E);
	/** Cards and list groups, between the base and raised surfaces */
	static final Color COLOR_SURFACE_CARD = new Color(0x232323);
	/** Raised pieces: tabs, the selected row */
	static final Color COLOR_SURFACE_RAISED = new Color(0x282828);
	/** A raised surface under the pointer */
	static final Color COLOR_SURFACE_HOVER = new Color(0x303030);
	/** Sunken wells */
	static final Color COLOR_SURFACE_INSET = new Color(0x161616);
	/** The player's own chat bubble */
	static final Color COLOR_SURFACE_USER = new Color(0x444444);
	/** An error bubble */
	static final Color COLOR_SURFACE_ERROR = new Color(0x4A2020);
	/** Slots, inputs, chips */
	static final Color COLOR_BORDER_DEFAULT = new Color(0x4D4D4D);
	/** The dark 1px edge around cards */
	static final Color COLOR_BORDER_EDGE = new Color(0x0F0F0F);
	/** Framed surfaces' outer line */
	static final Color COLOR_BORDER_OUTLINE = new Color(0, 0, 0, 128);
	/** Faint separators */
	static final Color COLOR_BORDER_HAIRLINE = new Color(255, 255, 255, 13);
	/** Rows inside a list card */
	static final Color COLOR_BORDER_DIVIDER = new Color(0x282828);
	static final Color COLOR_TEXT_PRIMARY = new Color(0xFFFFFF);
	static final Color COLOR_TEXT_BODY = new Color(0xC6C6C6);
	/** List row labels, default icon colour */
	static final Color COLOR_TEXT_SECONDARY = new Color(0xA5A5A5);
	static final Color COLOR_TEXT_MUTED = new Color(0x9A9A9A);
	static final Color COLOR_TEXT_ERROR = new Color(0xFF8A80);
	/** Squire blue, a shade lighter than the plume */
	static final Color COLOR_ACCENT_DEFAULT = new Color(0x4454DA);
	/** Accent edges and pressed states */
	static final Color COLOR_ACCENT_DARK = new Color(0x2F3AA6);
	/** Accent on dark: selection outlines, icons, links */
	static final Color COLOR_ACCENT_LIGHT = new Color(0x7A86FF);
	/** Finished, synced, unlocked */
	static final Color COLOR_STATUS_DONE = new Color(0x3FA33F);
	/** Background of finished checkpoints */
	static final Color COLOR_STATUS_DONE_SURFACE = new Color(0x2E4A26);
	/** Destructive actions */
	static final Color COLOR_STATUS_DANGER = new Color(0xF0625A);
	/** Level 99 */
	static final Color COLOR_STATUS_MAX = new Color(0xFFE066);
	/** Buttons */
	static final Color COLOR_BEVEL_LIGHT = new Color(255, 255, 255, 56);
	/** Buttons */
	static final Color COLOR_BEVEL_DARK = new Color(0, 0, 0, 89);
	/** Cards and slots */
	static final Color COLOR_BEVEL_CARD_LIGHT = new Color(255, 255, 255, 18);
	/** Cards and slots */
	static final Color COLOR_BEVEL_CARD_DARK = new Color(0, 0, 0, 61);
	static final Color COLOR_SERIES_1 = new Color(0x4454DA);
	static final Color COLOR_SERIES_2 = new Color(0x3F8FD6);
	static final Color COLOR_SERIES_3 = new Color(0x3FA33F);
	static final Color COLOR_SERIES_4 = new Color(0xC9483F);
	static final Color COLOR_SERIES_5 = new Color(0x8F6AD8);
	static final Color COLOR_SERIES_6 = new Color(0xE0922F);
	static final Color COLOR_SERIES_OTHER = new Color(0x6B6B6B);
	static final Color COLOR_SITE_BG = new Color(0x09090B);
	static final Color COLOR_SITE_BG_RAISED = new Color(0x0E0E11);
	static final Color COLOR_SITE_TEXT = new Color(0xECEBE7);
	static final Color COLOR_SITE_MUTED = new Color(0x8D8C95);
	static final Color COLOR_SITE_FAINT = new Color(0x5B5A63);
	static final Color COLOR_SITE_LINE = new Color(255, 255, 255, 18);
	static final Color COLOR_SITE_LINE_STRONG = new Color(255, 255, 255, 31);
	/** Chips, inputs, small buttons */
	static final int CORNER_SMALL = 2;
	/** Cards, buttons, slots */
	static final int CORNER_CARD = 4;
	static final int BORDER_WIDTH = 1;
	static final int BORDER_BEVEL = 1;
	static final int SPACE_2 = 2;
	static final int SPACE_4 = 4;
	static final int SPACE_6 = 6;
	static final int SPACE_8 = 8;
	static final int SPACE_10 = 10;
	static final int SPACE_12 = 12;
	static final int SPACE_16 = 16;
	static final int SPACE_22 = 22;
	static final int SPACE_33 = 33;
	static final int SPACE_44 = 44;
	/** A list row */
	static final int CONTROL_ROW = 32;
	/** A full-width button */
	static final int CONTROL_BUTTON = 40;
	/** An inventory slot or swatch */
	static final int CONTROL_SLOT = 44;
	static final int CONTROL_ICON = 16;
	static final int FONT_SIZE_SMALL = 11;
	static final int FONT_SIZE_BODY = 13;
	static final int FONT_SIZE_TITLE = 18;
	static final int FONT_SIZE_DISPLAY = 22;
	static final int FONT_SIZE_HERO = 44;

	private Tokens()
	{
	}
}
