# Download Mode

Small Android app that restarts a Samsung Galaxy phone straight into Download (Odin) mode,
the same as `adb reboot download`, without a PC.

A normal app cannot reboot the phone, so the command runs through
[Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api),
started with Wireless debugging (shell rights).

Kept on its own branch (`download-mode-app`); it is not part of FoldBlur.
