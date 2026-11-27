# Working rules for Claude on this repository

Read this file at the start of work, and again before every commit, before every
pull request, and before every merge or delivery of a pull request.

## Git: always rebase, never merge

- Never run `git merge` to bring `main` into a working branch. Never create a merge commit.
- Always rebase the branch on `main` instead:
  `git fetch origin main` then `git rebase origin/main`.
- Why: a merge keeps `main`'s history and adds a merge commit. When the same change was
  already brought into `main` under other commit hashes (cherry-pick, squash), the merge
  sees two versions of the same lines and creates conflicts. A rebase replays only this
  branch's own commits on top of `main` and skips the ones whose change is already in
  `main` (same patch, detected by patch ID). That is how a rebase avoids conflicts.
- Before rebasing, check what is already upstream with `git cherry -v origin/main HEAD`:
  a `-` means the same change already exists on `main`; a `+` means it is new.
- If a branch has a pull request open, rebase it on `main` before it is pushed, every time.
- Push a rebased branch with `git push --force-with-lease` (never a bare `--force`).
  Only push after the user has explicitly approved the push.
- Commit only after the user has approved the change. Never commit files that contain secrets.

## Commits

- Write commit messages in English: a short subject, then a short body explaining why.
- End each commit message with the trailer `Claude-Session: https://claude.ai/code/session_01RL5HtHmtSMXxM6egp1QWiF`.
- Do not write the name of the model in commit messages, pull requests, code comments or any
  file pushed to the repository.
- Before committing: run the unit tests and check they all pass. Do not commit with a failing test.
- Before committing: check that no temporary change is left (see the Gradle section below).

## Pull requests

- Before creating a pull request, check whether one is already open for the branch.
  GitHub accepts only one open pull request per branch: update the existing one instead.
- Create a pull request only when the user asks for it.
- Rebase on `main` before creating or updating the pull request, and check for conflicts
  (`git merge-tree --write-tree origin/main HEAD` must succeed) before saying it is ready.
- Title and description in English, very short.
- Description: a summary in bullet points, a test plan, then the line
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Update the description when the content changes (for example after a rebase, remove
  any mention of a merge).

## Merging and delivering a pull request

- Before a merge: rebase on `main`, confirm there is no conflict, confirm the tests pass.
- Do not merge a pull request yourself unless the user asks for it.

## Android build (Gradle)

- The project asks for a Java 25 toolchain; only Java 21 is installed in this environment.
  To build here, set `toolchainVersion=21` in `android/gradle/gradle-daemon-jvm.properties`,
  then restore the file with `git checkout -- android/gradle/gradle-daemon-jvm.properties`
  before any commit. Never commit that file with the value 21.
- Run unit tests with `./gradlew --no-daemon testDebugUnitTest` (from `android/`).
- The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Canvas (design mock-ups)

- Before republishing a page of the canvas, read its current version if someone else may have edited it.
- Never delete a view without the user's explicit approval.
