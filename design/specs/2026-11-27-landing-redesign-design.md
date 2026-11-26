# Install page redesign — design spec (2026-11-27)

Page: `docs/index.html` (GitHub Pages, https://osamudazai666.github.io/stremio-skip-intro/)

## Purpose
- **Audience:** anime fans (and series bingers) who use the Stremio desktop app on Windows.
- **The page's one job:** get them to copy the one-line installer, trusting it enough to paste it into Terminal.

## Direction: live player demo
The hero is a working mock player, and it's the page's one bold element.

**The demo loop:** a ~36 s fake episode with four parts: a recap (cold open), the opening, the episode, then credits and a preview.
- **During the opening:**
  - The intro segment on the seek bar turns yellow.
  - A Netflix-style "Skip Intro" button slides in.
  - Clicking it jumps past the opening.
- **During the credits:** "Watch Credits" and "▶ Next Episode" appear. Next Episode bumps the episode number and restarts the loop.
- **Subtitles:** lines in fansub style (Trebuchet MS Bold, yellow fill, black outline) carry the message, e.g. "You've watched this opening 27 times."
- **Art:** original CSS scenes only (layered hills, a sky that changes per segment). No real anime footage, logos or show branding.
- **Reduced motion:** no autoplay. The player shows a still frame inside the opening, with the button visible and working.

## Tokens
| Name | Hex | Role |
|---|---|---|
| Theater blue | `#0E1A2B` | Page background (the room around the screen) |
| Screen | `#070E18` | Player surface |
| Subtitle yellow | `#FFE14A` | The only accent: subtitles, the intro segment, focus rings, the primary action |
| Paper | `#EEF1F5` | Main text |
| Mist | `#8A97A8` | Secondary text |

**Type:**
- Trebuchet MS Bold for subtitles (the fansub vernacular, preinstalled on Windows).
- Segoe UI Variable / Segoe UI for body text.
- Cascadia Mono / Consolas for the command.
- No web fonts and no other outside requests.

## Layout
One column, left-aligned, max width ~1100 px:
1. **Top bar:** the name, and a GitHub link.
2. **Player demo:** 16:9.
3. **Install block:**
   - an h1 ("Skip intros in Stremio.") and one sentence
   - a primary "Copy install command" button
   - the command shown in a terminal-style line
   - an install.bat link and the requirement note
4. **"What you'll see":** one large annotated seek bar. Each segment (Recap, Intro, Episode, Credits, Preview) carries the button it triggers. This replaces the old table.
5. **"Install":** a numbered sequence (Copy → Paste in Terminal → Click Yes). It's a real sequence, so numbers are justified.
6. **FAQ:** native `<details>`, carrying over the existing answers.
7. **Footer:** an unofficial-project note and the data sources.

## Interaction and quality floor
- **Copy:** uses the Clipboard API, falling back to a text selection. A live region announces "Copied".
- **Keyboard:** the demo buttons are real `<button>`s, focus is visible (a yellow ring), and Enter works.
- **Responsive:** works down to 360 px; at phone width the annotated seek bar becomes a vertical list.
- **Contrast:** text contrast is AA or better against theater blue.
- **Restraint:** no scroll-triggered entrance animations. The only motion is the demo and responses to the user's own actions.

## Out of scope
- Real video, analytics, and translations.
