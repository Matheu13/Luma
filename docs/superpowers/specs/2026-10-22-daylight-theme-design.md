# Luma: Daylight theme (sixth theme, Apple-TV-style home)

Date: 2026-10-22. Status: design approved in conversation, awaiting written-spec review.
Build order: Daylight first, then the 1.2 TV setup (see 2026-10-22-tv-setup-design.md).

## Goal
Add a sixth theme, **Daylight**, inspired by the Apple TV home screen:
- a big top area for the focused favourite;
- rounded app tiles in rows, with every app on the home screen;
- a lift-and-tilt focus effect;
- a clean, light look.

The five existing themes stay exactly as they are. No Apple artwork, names or icons are used.

## Success criteria
- Appearance lists six themes. Choosing Daylight saves and restores like the others (`theme` index 5).
- Row 1 shows the seven favourites. Rows 2 and below show every other app. The top area always shows the focused favourite.
- Moving down scrolls the page smoothly and the top area slides out of view. Moving back up to row 1 brings it back.
- The focus effect is animated in Daylight only. The other themes still use 1.1.1's plain outline, with no motion.
- Animation stays smooth on the dev Fire TV, with no dropped frames visible during navigation.
- The screen reader still announces every tile with its name and position.

## Layout (1672×941 design space, the same as the other themes)
- **Wallpaper:** `app/assets/daylight-wallpaper.png` (1672×941). A soft, light pastel gradient with large blurred shapes, generated
  by a new script `tools/make-daylight.py` (Pillow), like `make-icon.py`. It is original artwork.
- **Top area** (y 0–470), shown when focus is in row 1 or the top bar:
  - Backdrop: the focused favourite's banner (or icon), scaled to cover the area and heavily blurred. It reuses the same
    shrink-then-enlarge trick as `frost`. It is washed with the app's `tone()` and fades into the wallpaper at the bottom.
  - The sharp banner at about 2× size on the left, with the app name (sans-serif-light, about 64) and "Open app  ›" below.
  - The top bar (Home, Apps, gear, clock) is drawn over it in white, as today.
- **Rows** (start at y 500):
  - Tiles are 200×120 with an 18 radius, 7 columns, a 28 gap, left 52, and a 40 gap between rows.
  - Tile content: the app's banner (aspect-fit), or its icon centred on a tile tinted with `tone()`.
  - The name is drawn only under the focused tile (dark text, about 22).
  - Row 1 is the favourites (`favourites`). Rows 2 and below are the apps not in the dock, in saved app order.
- **Scrolling:** the page offset eases (about 200 ms) so the focused row sits in view. When focus is on row 2 or below,
  the top area has scrolled off.
- **Focus effect** (Daylight only), about 150 ms ease-out between states:
  - scale 1.0 → 1.12 about the tile centre;
  - a soft drop shadow under the tile (blur, offset y);
  - a slight 3D tilt (`android.graphics.Camera` rotateY/rotateX, at most 4°) towards the last arrow pressed, easing
    back to flat;
  - a diagonal white shine gradient over the tile.

  The previously focused tile animates back down. While anything is animating, `onDraw` uses `postInvalidateOnAnimation`.
- **Light look:**
  - Text is dark (#1c1f26) on the light background, except the top area, which is white over the backdrop.
  - In Daylight, panels are light frosted (white, about 88%), with dark text and the accent `#3d7cf5`.
  - The hint line is dark grey.

## Remote and data
- The visible list in Daylight is favourites followed by the other apps. Columns are 7.
- Up from row 1 goes to the top bar. Down moves one row. Left and right wrap as today.
- **Hold OK:**
  - on row 1, the same options as today ("Move app", "Choose app for this slot");
  - on rows 2 and below, "Add to dock" (as in All apps) and "Move app".
  - Move mode in row 1 reorders the dock. In rows 2 and below it reorders the app order.
- Menu, Settings, All apps, Back and Home are unchanged.
- Accessibility: tiles stay virtual nodes. The spoken text says "dock N of 7" for row 1 and "app N of M" below.

## Code changes (`MainActivity.java`)
- `THEMES`, `TITLES` and `ACCENT` gain `daylight` / "Daylight" / `0xff3d7cf5`. The theme clamp becomes 0–5.
- `visible()` returns favourites followed by the rest when `theme==5 && !all`. Index mapping handles the two lists for
  launch, options and move.
- New drawing paths: `daylightHome(Canvas)` for the top area, tiles, focus effect and scroll; `daylightCard(...)`; light
  `drawPanel` styling when `theme==5`.
- Animation state: the focus scale per tile index, the scroll offset, and the tilt direction. All are time-based, from `now`.
- Other themes' drawing code is untouched.

## Testing
- Extend `tools/regression-audit.py`:
  - Appearance shows six items;
  - switch to Daylight, take a screenshot `theme-Daylight`;
  - press Down twice and check the page scrolled (a screenshot differs from row 1);
  - press Up back to row 1;
  - open a lower-row app with OK;
  - Hold OK on a lower row shows "Add to dock";
  - Daylight runs just before Nebula, so the audit still ends on Nebula as it does today.
- Manual check on the dev Fire TV: animation smoothness, and readability from the sofa.

## Out of scope
Live "Top Shelf" content from apps (Luma is offline); changing the other five themes; touch or pointer support beyond today's.

## Docs
README: add Daylight to the theme list, plus its screenshot once built. Theme count becomes six ("Six themes").
