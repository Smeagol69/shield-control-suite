# Codex working agreement

Read `docs/ai-collaboration.md` before changing this repository.

## Start every task

1. Run `git status -sb` and preserve unrelated user changes.
2. Run `git fetch origin --prune`.
3. Read the latest commits on `main` and any branch named in the handoff.
4. Create or continue an `agent/<task>` branch. Never work directly on another
   assistant's branch.

## Finish every task

1. Run the checks proportional to the change.
2. Audit the staged diff for credentials, signing material, generated output,
   device dumps, and unrelated files.
3. Commit with a focused message and push the branch.
4. Record the branch, commit, validation, remaining work, and any device-state
   changes in the pull request or handoff message.

Git history and pull requests are the shared memory between Codex and Claude.
Do not rely on private chat context that the other assistant cannot inspect.
