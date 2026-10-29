# WipeTogether settings

## Pick the right file

| I want to change… | Open… |
| --- | --- |
| Time before the team resets | `plugins/WipeTogether/config.yml` |
| Delay before generating the next standby world | `plugins/WipeTogether/config.yml` |
| Titles, subtitles, colors, and title timings | `plugins/WipeTogether/messages.yml` |
| Chat announcements or command responses | `plugins/WipeTogether/messages.yml` |
| Sidebar heading or TAB text | `plugins/WipeTogether/messages.yml` |
| Native hardcore mode | `server.properties`: `hardcore=true`, `difficulty=hard` |

**Safe editing:** stop the server → back up the file → edit → save as UTF-8 → start the server → check the console. Use spaces rather than tabs. A full restart applies changes; `/reload` is not supported.

Both configuration files are created automatically. Existing installations gain `messages.yml` on upgrade. You do not need to delete `config.yml`, worlds, or records to customize the presentation.

## Gameplay settings

These entries are inside the `settings` group in `config.yml`:

| Option | Default | Meaning |
| --- | --- | --- |
| `countdown-seconds` | `3` | Seconds before the next attempt; minimum `1`. |
| `cooldown-before-standby-prep-seconds` | `15` | Delay after a reset before preparing the next standby world; minimum `5`. |
| `world-prefix` | `wipetogether_world_` | World naming/cleanup prefix. Preserve it on existing installations. |
| `standby-prefix` | `wipetogether_standby_` | Generated run-world prefix. Preserve it on existing installations. |

The saved-data section below the divider is managed by the plugin. Keep `state`, `players`, `deaths`, and `wins` intact. World prefixes accept up to 80 letters, numbers, underscores, or hyphens.

## Edit the text in messages.yml

The file has four numbered sections:

1. **Chat announcements:** death, manual reset, and victory.
2. **On-screen titles:** victim, teammates, manual reset, welcome, and victory.
3. **Scoreboard & TAB:** sidebar heading, header, and running/completed footers.
4. **Admin command feedback:** permissions, usage hints, errors, and confirmations.

The `prefix` option supplies `<prefix>` wherever a message uses that placeholder. By default it is `[WipeTogether] `.

### Colors and styles

Text uses **MiniMessage**, not legacy `&c` color codes. Plain text also works. Close every formatting tag:

```yaml
prefix: '<gold>[My Team]</gold> '
```

Examples of text values:

| Text | Result |
| --- | --- |
| `'<green>Ready!</green>'` | Green text. |
| `'<gold><bold>NEW ATTEMPT!</bold></gold>'` | Bold gold text. |
| `'<#55ffff>Stay together</#55ffff>'` | A custom hex color. |
| `'First line<newline>Second line'` | Two lines. |

Keep the outer YAML quotes. Use double quotes if the text contains an apostrophe, or double the apostrophe inside single quotes. To display a literal placeholder-shaped word, escape its opening bracket with a backslash, for example `'Usage: /setrun \<number>'`.

### Title layout and timing

Edit an existing group under `titles`; do not add a second top-level `titles:` key. Here is a complete welcome group:

```yaml
titles:
  welcome:
    enabled: true
    title: '<gold><bold>Attempt #<run></bold></gold>'
    subtitle: '<gray>Stay together. Survive together.</gray>'
    fade-in-ms: 200
    stay-ms: 3000
    fade-out-ms: 500
```

| Group under `titles` | Who sees it |
| --- | --- |
| `countdown-victim` | The player who died, during the countdown. |
| `countdown-teammate` | Other online players, during the countdown. |
| `countdown-manual` | Everyone, during `/resetworld`. |
| `welcome` | Everyone moved into the new attempt. |
| `victory` | Players celebrating the completed run; repeated after the End return so credits do not hide it. |

`1000` milliseconds equals one second. All three timing fields accept whole numbers from `0` to `60000`. Countdown titles refresh once per second, so a long stay duration can be interrupted by the next countdown update or welcome title.

To hide one title, set its `enabled` option to `false`. A blank `title` or `subtitle` hides just that line. This does not stop the countdown, world reset, victory record, sounds, or fireworks.

The vanilla hardcore death screen and its Spectate World/Title Screen choices are separate from these titles and remain available.

### Placeholder reference

| Where | Available placeholders |
| --- | --- |
| Death chat and countdown titles | `<victim>`, `<seconds>`, `<run>`, `<prefix>` |
| Manual-reset chat and title | `<seconds>`, `<run>`, `<prefix>` |
| Countdown title colors | `<countdown_color>…</countdown_color>`: yellow, gold, then red. |
| Welcome title | `<run>`, `<prefix>` |
| Victory chat | `<run>`, `<time>`, `<team>`, `<seed>`, `<prefix>` |
| Victory title | `<run>`, `<time>`, `<prefix>` |
| Sidebar title | `<run>`, `<prefix>` |
| TAB header and footers | `<run>`, `<time>`, `<prefix>` |
| `commands.run-set` | `<run>`, `<prefix>` |
| `commands.deaths-set` | `<player>`, `<count>`, `<prefix>` |
| Other command responses | `<prefix>` |

Player names and other values are inserted as literal text. They cannot inject formatting or expand another placeholder. Placeholder names are case-sensitive. Use them only in the sections listed above.

### Hide a chat message

Use an empty quoted string for the desired entry:

```yaml
chat:
  victory: ''
```

This hides the announcement while still recording the victory and showing any enabled title. Edit the existing `chat.victory` entry rather than duplicating the `chat` group. Blank TAB fields clear the corresponding text; the plugin still manages the TAB header/footer.

## Defaults, upgrades, and troubleshooting

| Situation | Behavior / action |
| --- | --- |
| An option is missing | Its bundled default is used; custom files are not rewritten to add keys. |
| Unclosed MiniMessage tag | A startup warning identifies the option, and its default is used. Correct the tag and restart. |
| Invalid timing, boolean, or text type | A warning identifies the option; the default is used. Timings must be whole numbers, and enabled must be `true` or `false`. |
| Invalid YAML | Plugin startup stops, and the file is preserved. Correct the reported syntax and restart. |
| Text does not change | Check that you edited the server's active `plugins/WipeTogether/messages.yml` and performed a full restart. |
| Placeholder appears literally | Check its spelling and whether it is available for that message. |
| Restore the default presentation | Stop the server, back up/rename only `messages.yml`, and start again to create the current default file. |

The [bundled messages file](../src/main/resources/messages.yml) is the full reference. Presentation settings do not modify saved run identity, death counts, inventories, or win records. Command permissions remain enforced even when you customize their feedback.
