# Local Service Migration

## Safety Rules

- Install units first and activate them separately.
- Never run two Media services; they conflict on DLNA ports, DRM, Weston, and mpv sockets.
- Never run two USB services; they conflict on TCP 3240 and Windows attachment ownership.
- Do not restart USB while keyboard or mouse continuity is required.
- Do not modify `groot-schedule.service`, `schedule.json`, or `windows-c-mount`.
- Keep legacy units until the replacements have soaked and rollback has been tested.

## Stage Units

Merge the validated branch first so `~/.local/bin/groot` contains the new serve commands. The installer refuses an older target binary. A worktree-only deployment must set `GROOT_EXECUTABLE` explicitly and retain that worktree.

```bash
groot jobs deploy
systemd-analyze --user verify ~/.config/systemd/user/groot-{windows,media,usb}.service
```

## Windows Cutover

Windows service has no exclusive data-plane listener and can be enabled first:

```bash
systemctl --user enable --now groot-windows.service
groot windows status
groot windows exec 'Get-Date'
```

## Media Cutover

Only cut over while no playback is active. The installed legacy DLNA drop-in may pin `ExecStart` and `WorkingDirectory` to another worktree. Back up and retain that unit, drop-in, and pinned worktree for rollback; do not remove them during the first cutover. Copy the PipeWire environment into `media.env`.

```bash
systemctl --user cat groot-dlna.service
systemctl --user disable --now groot-dlna.service
systemctl --user enable --now groot-media.service
groot media status
```

Rollback:

```bash
systemctl --user disable --now groot-media.service
systemctl --user enable --now groot-dlna.service
```

This restores legacy behavior only while the old unit still points to its immutable legacy worktree.

## Display Retirement

Linux and Windows no longer share a monitor, so display arbitration is gone. After
the media cutover has soaked, remove the retired unit and its environment file:

```bash
systemctl --user disable --now groot-display.service
rm ~/.config/systemd/user/groot-display.service ~/.config/groot/services/display.env
systemctl --user daemon-reload
```

The `groot display` command is removed with it. Monitor brightness, volume, and
input source are handled by the monitor's own remote or OSD.

## USB Cutover And Legacy Cleanup

The Windows driver migration is explicit and is not run whenever USB service starts. During a maintenance window, stop the legacy agent before migration and before starting the new singleton:

```bash
systemctl --user stop groot-usb-agent.service
groot usb migrate-windows --force-global
systemctl --user disable groot-usb-agent.service
systemctl --user enable --now groot-usb.service
groot usb reconcile
groot usb list --json
```

An unplugged passthrough mouse or keyboard is expected to remain `waiting`; it must not be treated as a migration failure. Enabled virtual headset and speaker devices must still be exported and attached.

This migration is intentionally global to usbip-win2: it stops all retry requests, detaches all imported ports, empties the complete stash, and sets `ReattachMaxAttempts=0`. It is one-way unless the old stash and registry values were backed up manually. Do not run it when non-Groot USB/IP devices depend on persistence. If the command reports that a Windows restart is required, reboot Windows before treating the driver migration as complete.

After the new service has soaked successfully, remove the disabled legacy unit. It cannot be used after the nested `groot windows usb` command is removed:

```bash
systemctl --user disable --now groot-usb-agent.service
rm ~/.config/systemd/user/groot-usb-agent.service
systemctl --user daemon-reload
```

Reinstall the system-level passthrough unit so its `ExecStart` uses `groot usb passthrough-agent` before removing the nested command. This briefly restarts only the privileged passthrough backend; `groot-usb.service` remains running.
