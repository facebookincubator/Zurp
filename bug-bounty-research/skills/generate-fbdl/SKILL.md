---
name: generate-fbdl
description: |
  Generate valid FBDL scripts from natural language using the
  current block grammar, setup entities, and action rules.
---

# Generate FBDL

Generate an FBDL (Facebook Developer Language) script from the
user's scenario description.

## Instructions

1. Identify the setup entities needed first.
2. Identify the actions and the order they should occur in.
3. Use the block grammar below exactly.
4. If the `validate_fbdl` MCP tool is available, call it before
   presenting the script.
5. Return the script and a short explanation of what it sets up.

## Block grammar

```text
[setup]
  Type Label [with {key: value, ...}]
  Type Label [with {key: value, ...}]
[action]
  Subject [as VoiceSwitcher] action_name Label [with {key: value, ...}]
  Subject [as VoiceSwitcher] action_name Label [with {key: value, ...}]
```

- `[setup]` is required and must be on its own line.
- `[action]` is optional for setup-only scripts.
- Put one entity declaration per line under `[setup]`.
- Put one action per line under `[action]`.
- Lines starting with `#` are comments.

## Setup entities

- `User Label`
- `Page Label with {owner: UserLabel}`
- `Group Label with {owner: UserLabel, privacy: public|private|public_legacy}`
- `Album Label with {owner: UserLabel, type: user|shared|page|group, place: Label}`
- `Friendship with {sender: UserLabel, receivers: [UserLabel, ...]}`
- `Business Label with {owner: UserLabel}`
- `App Label with {owner: UserLabel}`
- `Event Label with {owner: UserLabel, place: Label}`

## Action guidance

Use the action name that matches the user's goal. Common categories:

- Posts: `make_post_text`, `make_post_photo`, `make_post_video`, `make_post_poll`, `make_post_violation`, `share_post`, `change_post_audience`
- Comments: `make_comment`
- Social: `friend_user`, `friend_request`, `follow`, `unfollow`, `block`, `like_post`, `like_page`, `going_event`
- Messaging: `send_message`, `create_chat_group`
- Groups: `create_group`, `add_group_members`, `add_group_admins`, `add_group_moderators`, `remove_member`, `mute_member`, `pause_group`, `unpause_group`, `set_post_permission`, `set_admin_approval`
- Pages: `create_page`, `publish_page`, `unpublish_page`, `add_page_facebook_access`, `add_page_task_access`, `set_age_restriction`
- Events: `create_event`, `add_event_cohosts`, `event_invite`, `cancel_event`
- Business: `create_business`, `add_biz_employees`, `add_biz_admins`, `create_ad_account`, `create_shop`, `create_shop_item`, `create_pixel`
- Apps: `add_app_admins`, `add_app_developers`, `add_app_testers`, `gen_token`
- Media: `add_cover_photo`, `add_story`, `create_album`, `add_photo`, `add_video`, `tag_photo`
- Collections: `create_collection`, `add_collection_contributors`, `save_to_collection`

## Rules

1. All labels referenced in actions must be created earlier in the script.
2. Users must exist before being assigned as owners or roles.
3. Friendships must exist before friend-dependent actions.
4. Voice switcher (`as PageLabel`) only works on actions that support it.
5. Labels should be unique PascalCase names.
6. Lists use bracket syntax: `[ItemOne, ItemTwo]`.
7. String values use single quotes: `text: 'Hello world'`.

## Output

Return the script in a code block plus a brief explanation of the setup and actions.
