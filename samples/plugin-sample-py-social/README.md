# plugin-sample-py-social

A `python`-kind sample plugin that provides `social.provider@1`
(docs/plugin-api.md 3 C19, docs/SPEC.md "Social") for a fake chat service:
three made-up friends, a standing you can change, and a friend who answers
each message a moment later. It proves the social plugin API end to end with
no account anywhere: the friends appear in the Social place and the
companion's Social tab next to Steam's, the Quick Menu's Social tile counts
their messages, and a message that arrives while its conversation is closed is
a notification.

- `src/plugin.py`: the whole plugin. `handle()` answers `account`, `friends`,
  `conversation`, `send` and `presence` on `social.provider`, and `panel` and
  `ping` on its Quick Menu panel (`ui.panel`). A fake friend's answer is
  announced with `droidtop.host.call("social", "changed", {...})`, which is how
  a provider with a live connection tells droidtop something happened.
  Standard library only.
- `manifest.template.json`: provides `social.provider` (label "Sample chat")
  and `ui.panel`; declares `notify.post`, without which messages only move
  the unread count.
- `test_plugin.py`: drives every op against a fake `droidtop` module
  (`python3 test_plugin.py` from this folder; CI runs it).
- `build.sh` and `sign.sh`: as for `plugin-sample-py-statustile`: build hashes
  `plugin.py` into `build/manifest.json`, sign packages
  `droidtop.sample-py-social.droidplugin.tar.xz` with the origin key (CI uses
  the `PLUGIN_SIGNING_KEY` secret). The Python runtime has to be downloaded
  once first (Settings, Plugins, "Download Python runtime").

Trying it: install the bundle, approve it (its list shows "Friends and chat"
and "Send you notifications"), open Social in the Gaming left menu. "Sample
chat" is under Accounts; Ada, Bo and Cy are under Friends. Write to Bo: the
answer arrives a moment later. On the Quick Menu, Plugins, Sample chat, "Get a
message" makes Bo write with the conversation closed, which raises a
notification and the Social tile's count.

## Access

It runs contained (docs/plugin-api.md 5.3). Its panel's "Notify me" posts a
notification through droidtop (`notify.post`); droidtop asks Android for its own
notification permission the first time, during that press, and keeps at most five an
hour per plugin.
