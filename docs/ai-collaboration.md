# Codex and Claude collaboration

Codex and Claude cannot share private conversation memory directly. This
repository provides the durable equivalent: source, commits, branches, pull
requests, checks, and explicit handoffs that either assistant can inspect.

## One task, one branch

- Codex: `agent/<short-task-name>`
- Claude: `claude/<short-task-name>`
- Stable integration branch: `main`

Never have both assistants edit the same branch. If one assistant is taking
over unfinished work, it should fetch the named branch, inspect its commits,
then branch from that commit under its own prefix.

## Start a session

```powershell
git fetch origin --prune
git status -sb
git log --oneline --decorate -12 --all
git switch main
git pull --ff-only
git switch -c agent/example-task
```

Claude uses `claude/example-task` in the final command.

## Hand work to the other assistant

Push the branch and provide:

```text
Repository: Smeagol69/shield-control-suite
Branch:
Commit:
Goal:
Completed:
Validation:
Device changes:
Remaining:
```

The receiving assistant runs:

```powershell
git fetch origin --prune
git show --stat <commit>
git diff main...<branch>
```

It reviews the change before continuing. Pull requests are preferred for work
that needs review or CI; their description should contain the same handoff
fields.

## Merge rules

1. The branch is pushed and the handoff is complete.
2. Relevant tests, lint, and builds pass.
3. No credential, token, keystore, ADB key, device dump, or generated cache is
   staged.
4. The receiving assistant or user reviews the diff.
5. Merge to `main`; do not force-push `main`.

## Local and mobile Claude

Claude Code can open this checkout directly and will read `CLAUDE.md`. A Claude
session without local filesystem access should use the private GitHub
repository, then work through branches or pull requests. In either case, give
Claude the repository name and the handoff block above.

## Conflict avoidance

- Split broad goals into separate branches by component.
- Keep commits focused and reversible.
- Never run destructive Git commands to resolve another assistant's work.
- If both assistants changed the same file, stop and review both diffs before
  resolving the conflict.
- Keep credentials in ignored local configuration only.
