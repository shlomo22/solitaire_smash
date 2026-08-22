# Solitaire Assistant — project state for Claude

Personal Android overlay helper for **Solitaire Smash** (draw-3 Klondike): captures the
screen, recognizes the board via template/color matching, computes a legal best move,
and draws a touch-through arrow overlay. See `README.md` for setup/build basics.

## Environment constraint — READ THIS FIRST

**This remote session cannot build, compile, or run the app.** `dl.google.com` (Android
SDK / Google Maven) returns HTTP 403 through the environment proxy, there's no
`/dev/kvm` or emulator, and no Android SDK is installed — only `java` and `gradle`.
`./gradlew :app:compileDebugKotlin` fails at plugin resolution even offline (AGP isn't
cached). This has been re-verified more than once; don't re-litigate it, just work
within it.

**Workflow that actually works, every round:**
1. Make a Kotlin change, reason carefully about correctness (see "Validation
   discipline" below).
2. Bump `versionCode`/`versionName` in `app/build.gradle.kts` (always — every push
   needs a distinguishable version so a screenshot proves which build ran).
3. Commit and push to the branch in use.
4. The user pulls, runs `./gradlew assembleDebug`, installs via `adb install -r`,
   opens the app's **Golden truth → Evaluate** button, and pastes back a screenshot
   + `analysis.log`.
5. Only real device evidence (the log, the golden-truth accuracy numbers, real pixel
   crops) counts as validation. Never claim a fix works without it.

`gradle.properties` pins `org.gradle.java.home` to the *user's* Windows JDK path —
don't edit it; override with `-Dorg.gradle.java.home=$JAVA_HOME` for any local
sanity check instead (brace-balance / structural checks only — it still can't finish
a real compile here, no AGP available).

## Validation discipline (this is what actually catches bugs before they ship)

Recognition-logic changes (rank/suit template matching, ink masks, scoring) must be
validated against **real golden-set pixels in a Python replica** of the exact Kotlin
algorithm *before* writing Kotlin — this has repeatedly caught mistakes that "looked
right" but weren't. Scratch scripts for this live under
`/tmp/.../scratchpad/facestep/` in past sessions (recreate as needed — it's not
checked into the repo). The loop: crop the exact region from
`app/src/test/resources/golden/*.png` using the bounds in the matching `*.json`,
replicate `inkMask`/`maskScore`/`tightContentCrop` in Python, confirm the numbers
match what the real device log reported, *then* fix the Kotlin and confirm the same
numbers change the way you expect.

Geometry constants (`BoardLocator.BoardGeometryProfile`: `cardAspect`, `faceUpOverlap`,
`faceDownOverlap`) are **high-risk** — they're global and affect every card position on
every board. A `faceDownOverlap` retune that looked justified by pixel measurement
caused a net on-device regression (1011→1005/1068) in a past round and had to be
reverted. Don't touch these without very strong, broad evidence, and expect to revert
if the next Evaluate run shows a net loss.

Concurrency/performance changes can't be validated via the Python-replica technique
at all (nothing to numerically compare) — treat them as a different, higher-uncertainty
risk category. Reason carefully about shared mutable state instead (see
`GameStateDetector.detect()`'s `computeColumn` for the current pattern: per-column-local
diagnostics/cache/counters, merged in order after `awaitAll()`).

## Recognition pipeline architecture

- **`GameStateDetector.detect()`** — top-level per-frame entry point. Locates the
  board, reads stock/waste/foundations sequentially, then recognizes the 7 tableau
  columns **concurrently** (added this session — see `computeColumn`, a local function
  whose diagnostics/recognizedSlots/slot-cache/counters are all column-local and merged
  back in column order after `awaitAll()` on a dedicated `columnExecutor`, not
  `Dispatchers.Default` — `detect()` is also called from `GoldenTruthEvaluator` via
  `withContext(Dispatchers.Default)`, so nesting `runBlocking` on that same shared pool
  would self-block). Per-slot recognition results are cached frame-to-frame
  (`slotHitCache`, keyed by `SlotKey(pile, index)`, fingerprinted by quantized color).
- **`CardRecognizer`** — rank/suit template matching. Two separate rank-template sets:
  `bitmapRankTemplates` (full-card) and `bitmapRankTemplatesTrimmed` (tableau cascade
  cards, capped to top 75% height — Queen's tail glyph doesn't fit in a cascade card's
  visible header strip). `bestBitmapRank` picks the top-scoring rank but declines
  (`return null`) on a close margin between #1 and #2, letting the caller fall through
  to OCR — this margin check now applies **regardless of the top score** (fixed this
  session; previously only applied below 0.68, which let a razor-thin 0.83-vs-0.82 tie
  win outright with no OCR chance).
- **`RankCornerOcr`** — ML Kit text recognition as a rank tiebreak fallback. Single
  shared instance, `@Synchronized` (added this session once tableau columns started
  running concurrently — ML Kit's client isn't documented safe for concurrent calls).
- **`DeckConstraintPass`** — post-processing pass enforcing "each rank+suit appears at
  most once." `resolvePartnerSuitSwaps` used to reconsider *any* two same-rank,
  same-color cards with different suits, even when both reads were already confident —
  it corrupted a correct 0.84-confidence Seven-of-Spades into Seven-of-Clubs this way.
  Now only reconsiders a pair when at least one side's `suitAmbiguous` flag was already
  set.
- **`GoldenTruthEvaluator`** — the Evaluate-button harness. `findMatchingSlot` matches
  truth to detected slots by pile + nearest centroid (80px), excluding inferred slots.
  **Known artifact, not a bug to chase**: when the rightful card's own read gets
  rejected/excluded, a correctly-read *neighboring* card can steal its truth match,
  producing a confusing mismatch line that isn't representative of a real recognition
  error (established case: `8D vs 7C` at a specific tableau:3 slot, persisted unchanged
  through many fix rounds — confirmed via Python replica that the real scores are
  genuinely weak there, the system is correctly declining to guess).
- **Golden set data quality**: the golden JSON/PNG samples under
  `app/src/test/resources/golden/` are human-labeled and **can themselves be wrong**.
  Found this session: sample `20260814_205456`, `tableau:0` index 2 is truth-labeled
  "Jack of Clubs" but the actual pixel crop is unambiguously Jack of **Spades** (visible
  spade pip) — confirmed by cropping and viewing the real PNG. Before concluding a
  mismatch is an app bug, crop and visually check the actual pixels; don't trust the
  truth label blindly.

## Current state (as of v1.3.68 / versionCode 69, pushed, not yet device-verified)

Last confirmed-on-device accuracy: **97% (1031/1068)** on the golden set, before the
two fixes in this push (tableau-column parallelization already confirmed working
correctly on-device at this accuracy; the `DeckConstraintPass` suit-swap fix and the
`bestBitmapRank` margin fix are pushed but awaiting the next Evaluate run).

Remaining confusion buckets worth investigating next, in roughly descending value:
- `Eight → Seven` (2) — this is the known `GoldenTruthEvaluator` neighbor-match
  artifact above; not expected to be fixable by touching recognition.
- `Seven → Jack` (2) — a genuinely weak rank read (`rank-png@0.60`, "7" not even in the
  top-4 candidates) that still clears the 0.55 reliability floor
  (`TableauCascadeSupport.MIN_READ_CONFIDENCE`). Not yet root-caused.
- Long-cascade compounding errors (e.g. sample `20260819_211539` tableau:3, a 12-card
  cascade with several consecutive wrong reads) — possibly the same kind of geometric
  position drift that caused the Five/Six bug (each card's position is
  `firstFaceTop + exposedIndex * faceUpStep`, purely arithmetic, never re-measured per
  card — small drift compounds over a long run). Worth checking with the same
  Python-replica-at-the-geometric-position technique used for the Five/Six fix.

## Don't

- Don't retune `BoardGeometryProfile` constants without very strong, broad (not
  single-sample) evidence — see "Validation discipline" above.
- Don't treat a golden-set mismatch as a confirmed app bug without cropping and
  visually checking the real pixels first.
- Don't suggest stopping/wrapping up preemptively — this project runs as a long,
  iterative fix-verify loop and the user has consistently wanted to keep going past
  the point earlier sessions guessed was a reasonable stopping point.
