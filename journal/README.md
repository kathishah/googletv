# journal

A dated development log: what was found or decided, and why, as it happened. Most entries record investigations of the Mac mini's Photos library, the SMB share, and the TV.

**Naming:** `YYYY-MM-DD-HHMM-topic.md`, e.g. `2026-10-12-1729-mac-mini-signing-change.md`. The timestamp is when the entry was first recorded. Entries moved out of `docs/` on 2026-10-19 use the time of the commit that first recorded the text. Per-day entries named `device-findings-log` hold that day's rows of the old device test table.

**Rules:**

- One entry per unit of work or finding. Don't batch unrelated findings into one file.
- Not edited after the fact. If an old entry turns out wrong, write a new entry saying so; the log stays an honest record of what was known at the time.
- This is a log, not a spec. Durable decisions belong in `docs/` (for example `docs/PRODUCT.md`), which should link back here for the evidence. Dated device test results are entries here too; `docs/DEVICE_TESTING.md` keeps the runbook and the release checklist.
- No photo filenames, host addresses, device identifiers or credentials, as everywhere else in the repository (see `AGENTS.md`).

## Retired planning and review documents

These were moved here from `docs/` on 2026-10-19 when the docs were reduced to current state.

- Roadmap: [roadmap history](2026-10-19-2246-roadmap-history.md)
- Code review: [review](2026-10-19-2051-app-review.md) and [fix plan](2026-10-19-2051-app-review-implementation-plan.md)
- Existing-app survey: [survey](2026-10-11-2143-existing-app-survey.md)
- Album selection: [progress](2026-10-17-1455-album-selection-progress.md), [remaining checks](2026-10-17-1455-album-selection-remaining-checks.md), [refresh investigation](2026-10-17-1455-album-refresh-performance-investigation.md), [refresh timings](2026-10-17-1617-album-refresh-timings.md)
- Media type: [implementation plan](2026-10-17-2012-media-type-implementation-plan.md), [catalog v2 and v3](2026-10-17-2012-media-type-catalog-v2.md), [open questions](2026-10-17-2012-media-type-open-questions.md)
- Portrait pairs: [implementation plan](2026-10-18-1355-portrait-pairs-implementation-plan.md), [decisions to confirm](2026-10-18-1355-portrait-pairs-decisions-to-confirm.md)
