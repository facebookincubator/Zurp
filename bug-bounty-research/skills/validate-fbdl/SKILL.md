---
name: validate-fbdl
description: |
  Validate FBDL scripts against the current block grammar,
  entity requirements, and action/reference rules.
---

# Validate FBDL

Validate an FBDL script and report any errors clearly.

## Instructions

1. If the `validate_fbdl` MCP tool is available, use it.
2. Otherwise validate manually with the checklist below.
3. Report errors with line numbers and specific explanations.
4. If the script is valid, confirm that and briefly explain what it does.
5. If the script is invalid, provide a corrected version.

## Validation checklist

### Structure

- Script starts with `[setup]`
- `[setup]` is on its own line
- `[action]`, if present, is on its own line
- One entity declaration per line under `[setup]`
- One action per line under `[action]`
- Setup-only scripts are valid
- Lines beginning with `#` are comments

### Setup entities

- Entity type is known: `User`, `Page`, `Group`, `Album`, `Friendship`, `Business`, `App`, `Event`
- Every labeled entity has a label
- `Friendship` has no label
- Required params are present:
  - `Page`: `owner`
  - `Group`: `owner`, `privacy`
  - `Album`: `owner`, `type`, `place`
  - `Friendship`: `sender`, `receivers`
  - `Business`: `owner`
  - `App`: `owner`
  - `Event`: `owner`, `place`

### References and syntax

- Action name is known
- Subject exists
- Required keyword params are present
- Voice switcher is only used on actions that support it
- Referenced labels were defined earlier
- Keyword blocks use `{key: value}`
- Lists use `[item1, item2]`
- String values use single quotes
- Labels are unique and PascalCase

## Output

If valid:

> Script is valid. It creates [brief setup summary] and performs [brief action summary].

If invalid:

> Found N error(s):
> - Line X: [specific error]
> - Line Y: [specific error]
>
> Corrected script:
> ```text
> [corrected script]
> ```
