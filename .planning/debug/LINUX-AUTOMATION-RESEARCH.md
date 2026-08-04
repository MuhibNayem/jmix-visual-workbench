# Linux GUI Automation Research — What This Machine Needs

**Date:** 2026-08-04
**Context:** The Qwen Code `computer_use` tools are backed by **cua-driver** (trycua/cua, Rust driver). On this host (Ubuntu 24.04, GNOME Shell 46, Wayland session, IntelliJ IDEA 2026.2 running native Wayland) the driver currently returns zero windows and no screenshots. This note documents the verified requirements from the cua project's own docs (`libs/cua-driver/docs/linux-desktop-validation.md`, `docs/linux-support-completion-plan.md`, `docs/action-support.md`, installer scripts at cua.ai).

## Verdict

cua-driver supports **GNOME 46/Mutter Wayland** (validated upstream: full GTK3 matrix 31/31, foreground click/type/keys/hotkeys/drag/scroll, background actions via AT-SPI, stage capture) but it is **pre-release** and requires host components that are currently missing or disabled on this machine.

## What is already present (verified)

| Piece | Status |
|---|---|
| xdg-desktop-portal 1.18.4 + gnome/gtk backends | installed & running |
| libei 1.2.1 / libeis (input-emulation protocol) | installed |
| at-spi2-core 2.52 (accessibility D-Bus bus) | installed |
| GNOME Shell 46 Wayland user session | active |

## What is missing (the actual gaps)

1. **cua-driver binary is not installed.** No `cua-driver` on PATH, nothing in `~/.cua-driver/`. Install (official installer; pipes a remote script to bash):
   ```bash
   /bin/bash -c "$(curl -fsSL https://cua.ai/driver/install.sh)"
   ```
   The Linux tarball contains, besides the binary: `cua-cursor-theme` and the **WinRects GNOME Shell extension** (`winrects@cua`, installed to `~/.local/share/gnome-shell/extensions/winrects@cua`).

2. **WinRects helper extension not enabled.** On GNOME Wayland it provides window IDs, geometry, stacking, activation, and **stage capture (accurate screenshots)**. Without it, target-bound foreground input is refused. After install/update the **GNOME session must be restarted** (logout/login — no in-place shell restart on Wayland).

3. **GNOME toolkit accessibility was OFF** — apps do not expose AT-SPI trees. Enabled on 2026-08-04:
   ```bash
   gsettings set org.gnome.desktop.interface toolkit-accessibility true
   ```
   This is what lets AT-SPI enumerate/activate IntelliJ's Swing UI (Java exposes AT-SPI through the ATK bridge when assistive tech is enabled).

4. **Agent skills not installed.** cua-driver ships per-OS skill bundles (MACOS.md / WINDOWS.md / LINUX.md):
   ```bash
   ~/.local/bin/cua-driver skills install
   ```

5. **Run context:** driver commands must start from the graphical user's systemd user session (or a terminal inside it). Optional auto-start: re-run installer with `--autostart` (systemd user unit).

## How the pieces map to capabilities (GNOME Wayland)

| Capability | Backend used |
|---|---|
| Window list / geometry / activation | WinRects GNOME extension |
| Screenshots / stage capture | WinRects capture (portal video capture still an open gap upstream) |
| Foreground mouse/keyboard injection | xdg-desktop-portal + libei (persistent session) |
| Element tree + background semantic actions | AT-SPI (needs toolkit-accessibility=true) |

## Known limitations (upstream, as of research date)

- Linux support is pre-release; real-Xorg and KDE behavioral lanes are not accepted upstream.
- Unicode text injection and parallel-drag are unproven; ASCII text and named keys/hotkeys work.
- WebKitGTK/Tauri/JCEF-in-GNOME capture evidence is incomplete upstream (DRM/EGL gaps) — JCEF content inside IntelliJ may capture imperfectly until portal video capture lands. This matches the observed "screenshots show incorrect screens" symptom when using generic capture routes.
- Alternative fallbacks that do NOT need the cua stack: `ydotool` (uinput daemon, needs root/uinput access) for input; AT-SPI tooling (dogtail/python3-atspi) for element automation; xdg-desktop-portal Screenshot (interactive consent dialog) for capture.

## Recommended enablement sequence (touches the user session)

1. Install cua-driver via the official installer script.
2. `cua-driver skills install`.
3. `gsettings set org.gnome.desktop.interface toolkit-accessibility true` (done).
4. Log out and back in (activates `winrects@cua`).
5. Verify: `cua-driver --version`, then a window-list call should return GNOME/IDEA windows and per-window capture should produce accurate screenshots.
