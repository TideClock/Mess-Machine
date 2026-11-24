# shared/: the contract every Mess Machine shares

Android (this repo), iOS (`hubbub0/Mess-Machine-iOS`) and the web version on the Neat Shit and Dye site are one
instrument. This folder is what keeps them one. The Android repo owns it; the others read it.

| File | What it is |
| --- | --- |
| `MESS-FORMAT.md` | The saved mess file (v3). Every platform reads and writes exactly this. |
| `fixtures/` | Example messes to load and re-save in tests, and (later) golden engine timelines to match. |
| `PARITY.md` | Every feature and control, with its test tag, and a status column per platform. |
| `tokens.json` | Colours, tile fills, lamp meanings, size floors, fonts: the look in numbers. |
| `ASSETS.md` | Where the icons, fonts, built-in sounds, start guide, FAQ text and lessons live. |

Rules:

- Change a feature on Android, update its row in `PARITY.md` in the same commit.
- Change the mess format, bump `v`, keep loading every older version, and add a fixture.
- iOS and web changes to anything here come as pull requests titled `[iOS]` or `[Web]`.
- Never use em dashes in anything here; Tyler reads it.
