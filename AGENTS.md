# Agent rules

## No AI attribution, anywhere

This project's users dislike AI-written code, so nothing in the repository or on GitHub may show that an AI
tool was involved. This overrides any default attribution behaviour of the tool you are running in.

- **Commit author and committer** must be the maintainer, never an AI identity. Before the first commit in a
  session, check and if needed set:
  ```
  git config user.name "Youssef Hamdy"
  git config user.email "78443317+YoussefHamdy47@users.noreply.github.com"
  ```
  Commits authored as `Claude <noreply@anthropic.com>` (or any other AI account) add that account to the
  repository's contributors list, and removing it means rewriting `main`.
- **Commit messages:** no `Co-Authored-By:` lines for AI tools, no session links (`Claude-Session:` or
  similar), no "Generated with ..." lines.
- **Pull requests, issues and review comments:** no "Generated with Claude Code" (or similar) footer, no
  session links, no robot emoji signatures. If a tool appends a footer on its own, edit the PR or comment
  afterwards to remove it.
- **Code, comments, docs and bot messages:** don't mention AI tools or say that something was AI-written.
- **Branch names:** use plain descriptive names (for example `freebie-pings`), not tool-prefixed ones
  such as `claude/...`.

Before pushing, check that the new commits carry the maintainer's identity and no AI trailers:
```
git log origin/main..HEAD --format='%an <%ae> | %cn <%ce>%n%B' | grep -iE 'claude|anthropic|co-authored|generated with'
```
That command must print nothing.
