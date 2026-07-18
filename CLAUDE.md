# Claude working agreement

Read `docs/ai-collaboration.md` and `AGENTS.md` before changing this repository.

Use `claude/<task>` branches for Claude-authored work. Before editing, fetch
`origin`, inspect `main`, and review any Codex branch or commit named in the
handoff. Never overwrite uncommitted user work or continue on an `agent/*`
branch.

Before handing work back:

1. Run the relevant tests, lint, and build checks.
2. Inspect the staged diff for secrets, signing files, generated output, device
   dumps, and unrelated changes.
3. Commit and push the `claude/<task>` branch.
4. Provide the branch, commit SHA, validation results, remaining work, and any
   Shield state changed.

Git commits and pull requests are the authoritative shared context. Do not
assume Codex can see Claude chat history.
