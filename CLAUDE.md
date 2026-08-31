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
3. **Always commit and push in the same turn** once the version is bumped — do not
   leave a version bump sitting uncommitted or wait for a separate "commit and push"
   request. That push is part of finishing the round, not an optional follow-up.
4. The user pulls, runs `./gradlew assembleDebug`, installs via `adb install -r`,
   and taps **Golden truth → Evaluate**. Then they run
   `powershell -ExecutionPolicy Bypass -File scripts\pull-artifacts.ps1`, which
   writes `analysis.log` + `screenshot.png` to `pulled/<timestamp>/` and copies
   the same pair to **`pulled/latest/`**.
5. Only real device evidence (the log, the golden-truth accuracy numbers, real pixel
   crops) counts as validation. Never claim a fix works without it.

### "Read latest pull" / "pull artifacts"

When the user says **read latest pull** (or "latest pull", "look at the latest
evaluate"), read these two files — do not wait for a pasted screenshot or an
older `pulled/<timestamp>/` folder:

- `pulled/latest/screenshot.png` — Evaluate UI (version, 5169/5317-style totals,
  confusion buckets, example mismatch lines)
- `pulled/latest/analysis.log` — full mismatch traces (`rank=`, `suit=`,
  `post=[...]`, `diag=`, `bottom-repair:`, `deck-constraint:`, `geom-override:`,
  `probe=`)

When the user says **pull artifacts** (or "run pull-artifacts", "pull and
read"), first run from the repo root:

```
powershell -ExecutionPolicy Bypass -File scripts\pull-artifacts.ps1
```

That copies `analysis.log` + a screen capture into `pulled/<timestamp>/` and
`pulled/latest/`. Then read `pulled/latest` as above. Needs a connected device
with the debug app installed and Evaluate already tapped.

`pulled/` is gitignored. Prefer `pulled/latest` over chat image attachments from
earlier rounds; those can be a previous Evaluate.

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
- **Black-suit ambiguous tiebreak has two independent passes, and the second one is not
  where the wrong answer usually comes from.** `CardRecognizer.ambiguousBlackSuit()` (the
  *first* pass, inside the main `recognize()` call) computes `blackSuitScoresFromCrop` and
  tries `resolveBlackSuitLeader`; when that genuinely can't pick a side (e.g.
  `branch=lowTopMargin-noShape->ambiguous`, a real near-tie like `full=C0.90/S0.91`), it
  does **not** stay unresolved — it falls through to `bestBitmapSuitLoose`, and failing
  that, a bare `if (scores.fullSpade >= scores.fullClub) Suit.Spades else Suit.Clubs`
  coinflip. That coinflip is what actually picks the suit stored on the card
  (`suitAmbiguous=true`, but a concrete suit is already assigned). A *second* pass,
  `GameStateDetector.resolveCardSuitWithTrace`/`resolveBlackSuit`, re-fires afterward on
  whatever region the caller passed (for tableau cascade cards, the ~44px trimmed
  rank-header strip) any time a card comes back ambiguous, and normally either confirms
  or overrides the first pass's answer. Confirmed this session (golden sample
  `20260819_211539` tableau:2, King of Clubs pixel-verified, misread as King of Spades):
  gating the second pass to skip re-checking when the first pass already had a strong
  absolute score (`STRONG_AMBIGUOUS_SUIT_FLOOR = 0.80f` in `GameStateDetector`) is a
  real, harmless improvement in principle, but it was a **no-op** for this exact case —
  the wrong "Spades" was already locked in by the first pass's own coinflip fallback
  before the second pass ever ran. If you're chasing a black-suit-ambiguous miss, check
  `diag=match-<rank>-<suit>-ambiguous@...` (set once, at the *first* pass) before
  assuming a fix aimed at the second-pass recheck will change it. The likely next lever
  is either the first pass's own `bestBitmapSuitLoose`/coinflip fallback, or teaching
  `DeckConstraintPass` occupancy, or a header-strip re-vote. **Parked as of v1.4.80.**
  Two weeks of those levers net-regressed (see Current state). Occupancy cannot
  break the 10C/KC/QC boards — both black ids are free. Leaving suit unassigned
  on a near-tie would only suppress foundation arrows (tableau only needs color)
  and would drop Evaluate. Do not spend another round on C↔S scoring.
- **`TableauCascadeSupport.isReliableRead`** gates whether a mid-cascade slot uses its
  own direct rank/suit read or falls back to `geometricCascadeCard` (bottom-card rank +
  arithmetic distance). It used to check only a flat `MIN_READ_CONFIDENCE = 0.55f` —
  root-caused this session (golden sample `20260818_080819` tableau:6, a genuine
  Ten(♠)-Nine(♦)-Eight(♣)-Seven(♦)-Six(♣) run): the bottom card (Six) and the leading/
  most-covered card (Ten) are read correctly and independently, and agree cleanly on a
  5-card run, but 3 of the 4 covered middle cards still get misread (various Ten/Jack/
  Queen guesses at confidence 0.57–0.65, one with the true rank not even in the top-4
  candidates) because 0.55 alone doesn't know a better, doubly-anchored answer already
  exists. Fixed by computing `rankCountConsistent` (true when the bottom and leading
  cards' rank arithmetic already agrees on the run length) and, when a mid-run read both
  contradicts what that consensus predicts *and* stays below a stricter
  `STRONG_DIRECT_READ_FLOOR = 0.75f`, preferring the geometric fallback instead. A read
  that's either strong (≥0.75) or already agrees with the consensus is untouched. Pushed
  as v1.4.20/91, not yet device-verified — this is the likely fix for the `Seven→Jack`
  bucket and probably other silent mid-run misreads sharing the same mechanism.
- **`GoldenTruthEvaluator`** — the Evaluate-button harness. `findMatchingSlot` matches
  truth to detected slots by pile + nearest centroid (80px), excluding inferred slots.
  **Known artifact, not a bug to chase**: when the rightful card's own read gets
  rejected/excluded, a correctly-read *neighboring* card can steal its truth match,
  producing a confusing mismatch line that isn't representative of a real recognition
  error (established case: `8D vs 7C` at a specific tableau:3 slot, persisted unchanged
  through many fix rounds — confirmed via Python replica that the real scores are
  genuinely weak there, the system is correctly declining to guess).
- **`AnalysisPipeline.applySuggestionStickiness`** damps single-frame arrow flicker: when
  the previously-shown move drops out of the ranked list, it holds the *old* arrow on
  screen for up to 2 frames before adopting the new best move (built for a genuinely
  static board where a buried tableau card's rank flickers between two reads for one
  frame). Bug found and fixed this session (v1.4.16/87): Waste/Stock change on every
  draw, so a vanished `Waste -> Foundation`/`Waste -> Tableau` move almost always means
  the player already drew past it, not a misread — holding froze the arrow at the old
  waste position while the visible card underneath kept changing, which is what a user
  report described as an arrow connecting two nonsensical ranks (e.g. "J → 3"). Waste-
  sourced vanished moves now adopt the new best move immediately; tableau-sourced ones
  keep the original 2-frame grace period.
- **Golden set data quality**: the golden JSON/PNG samples under
  `app/src/test/resources/golden/` are human-labeled and **can themselves be wrong**.
  Found this session: sample `20260814_205456`, `tableau:0` index 2 is truth-labeled
  "Jack of Clubs" but the actual pixel crop is unambiguously Jack of **Spades** (visible
  spade pip) — confirmed by cropping and viewing the real PNG. Before concluding a
  mismatch is an app bug, crop and visually check the actual pixels; don't trust the
  truth label blindly.
- **Golden-set batch corruption (new, worse variant of the truth-quality issue)**: a
  26-sample "new golden" push this session included 5 files
  (`20260824_032620`, `20260824_033921`, `20260824_034448`, `20260824_034809`,
  `20260824_041306`) where 56-91% of every slot's truth was simply wrong — not a
  labeling slip, but truth that looked like it belonged to a *different* board state
  entirely. Confirmed via pixel crop on two of them: the **engine's own read matched
  the real pixels**, truth didn't (one showed 6 real face-down cards + one Ten of
  Hearts; truth claimed 7 different face-up cards). The other 21 files in the same
  push were fine (0-24% mismatch, consistent with the existing baseline). Diagnostic
  signal: compute mismatch-rate per file across a "new golden" push before trusting
  any of it — a handful of files at 60%+ while the rest sit near the baseline rate
  means truth/PNG pairing broke for just those files, not a recognition regression.
  User deleted the 5 bad files and re-pushed (commit `4f4b393`); don't resurrect them.
- **Waste-pile Six is a real, large, still-unfixed recognition gap**: across the whole
  golden set, 13 waste samples have truth rank Six; only 2 are read correctly (85%
  failure rate), misread mostly as Nine, sometimes Four or Eight — across different
  suits and different sessions, so it's systemic, not one bad crop. Root-caused via a
  faithful Python replica of `CardRecognizer.exactRankTemplateScores`
  (`inkMask`/`maskScore`, 48×48 Dice coefficient, ±2px shift): the four existing
  `rank_six*.png` templates score only ~0.41-0.42 against a real Six, while Nine's
  templates score ~0.44-0.45 on that same crop — Six is losing by a persistent ~0.03
  margin, not close to a coinflip. **Three candidate fix templates were built and
  rejected, all for the same reason**: each was assembled from a real, pixel-verified
  Six crop (a full corner-region crop, a tightly-isolated glyph, and a pip-masked
  version), and each scored 0.8-1.0 against genuine Sixes — but when checked against
  the *other* ~30 non-Six waste samples in the golden set, every one scored higher
  than the correct rank's own template on 65-95% of them. All three were matching
  generic card/border/ink-density structure shared by every waste crop, not the "6"
  shape specifically. **Lesson for next attempt**: validating a new bitmap template
  only against the rank it's meant to fix is not enough — it must also be checked
  against a broad sample of *other* ranks for false positives, the same way a
  geometry-constant change needs broad-not-single-sample evidence. No fix shipped;
  the likely better lever is a `WasteRankCorrections`-style override rule using the
  measured ~0.03 score gap (matching how existing rules already handle Four/Jack and
  Six/Four confusions there), not another template.
- **Waste fan: OCR on a left crop reads the covered 2nd card as waste top.** Draw-3
  waste fans leftward; only the rightmost face is playable. `wasteTop()` is a single
  slot. `attemptWasteRankOcr` tries tight, then legacy, then `wasteOcrCardRegions`
  (ink-anchored is left of the front card). After both template crops miss or agree
  on the front rank, a later region can OCR the peeking neighbor at ≥0.62 and
  `ocrRankOverride`'s `isConfusionPair` fallback adopts it. Pixel-confirmed:
  `20260824_080444` fan is 8♠ / **10♥** / **Q♥** (playable), engine `QH vs 10H`
  (`waste-ocr-rank:Ten` from `whole@707` / `whole@700`); `20260825_131401` fan is
  Q♥ / **10♦** / **Q♣**, engine `QC vs 10C`; `20260823_230337` fan is K♣ / **3♥** /
  **J♠**, engine `JS vs 3C`. This is also the live-play false Waste→Tableau arrow
  (legal for the covered card, illegal for the exposed one). `locateWasteTopRegion`
  can independently latch the 2nd card: it counts only white/red ink, so a black
  front card can fail the 6% gate. **v1.4.81–1.4.84 gated this:** skip
  `isConfusionPair` when both crops already ranked; keep Jack on Jack+Four when
  neighbor OCR is 5/3; OCR Jack may override a tight Four. Queen/Ten and Jack+Three
  fan cases cleared. Remaining rank misses are mostly **no-OCR** Four→Six
  (`230705` JD, `132126`/`132140` 8D).
- **Root-caused the no-OCR Four/Six/Four cases above: waste OCR crops start flush
  at the card's own top edge, landing on a drop-shadow band that
  `RankCornerOcr.preprocess()` paints as ink.** Pixel-checked all four named
  no-OCR samples (`230705` JD, `132126`/`132140` 8D, `080754` 5D — all four
  correct-per-truth, engine misread as Four/Six/Four): rows 0-5 of every waste
  OCR region (both the WASTE-profile whole-region ROI and
  `BoardLocator.wasteRankCornerRegion`'s DIRECT-profile corner ROI — both
  card-top-anchored) are a desaturated dark-purple drop-shadow/border-transition
  band (~RGB(50,36,98)) that passes `SmashColorAnalyzer.isBlackInk`, so
  `preprocess()` paints a spurious solid black bar across the top of the
  binarized crop handed to ML Kit, sitting directly above the rank glyph (which
  never starts before ~6.7% of card height in these four samples — verified via
  a Python replica of the exact ink-mask/preprocess pipeline against the real
  golden PNGs). **v1.4.85** insets both ROI paths' top edge by 4% of card
  height to skip the band. Python re-simulation confirms the digit comes out
  fully isolated with no artifact once inset. Not yet device-verified — this is
  a preprocessing-noise fix, not a template/score change, so it can't be
  validated further offline; next Evaluate run is the real test.
  **Correction on `080754`**: it turned out not to be a true no-OCR case — the
  real-device log behind v1.4.86 shows OCR already read `'5'@0.62` there; it
  just had no override rule to act on it (fixed by v1.4.86 below, independent
  of the shadow-band inset).
  **v1.4.89 Evaluate result on this theory**: `230705` no longer mismatches
  (fixed — mechanism unconfirmed, since a correct read leaves no trace in the
  mismatch-only log) and `080754` no longer mismatches (confirmed v1.4.86, per
  above). But `132126`/`132140` (8D vs 6D) **still mismatch, and the real
  device log still shows `ocr=miss:empty` on all 10 whole/corner region
  attempts** — the shadow-band inset did not fix this case. The theory is
  falsified for this specific sample: whatever blocks ML Kit here, it isn't
  (only) the drop-shadow band. v1.4.90 (pulled, not yet Evaluate-verified)
  takes a different angle on the same sample — stops `correctSixOnWaste`'s
  silent 0.38-confidence Six-steal from firing on a genuine OCR miss and lets
  Eight's own ink/template score win instead, sidestepping the OCR failure
  rather than fixing it. Next Evaluate run is the real test of whether that
  lands where the geometry fix didn't.
  **Correction on "falsified": the shadow-band theory wasn't wrong, it was
  incomplete.** v1.4.91 found v1.4.90's ink/score Eight-from-Four guess cost
  net −30 (new Four→Eight and Six→Eight false positives) and reverted just
  that part, keeping the no-OCR Four→Six gate. v1.4.92 then went back to the
  ROI itself: `RankCornerOcr`'s WASTE profile and `wasteRankCornerRegion` were
  both still only 0.32/0.30 tall — tall enough to clear the drop-shadow band
  but not tall enough to fit a full Smash "8" (two stacked loops). That
  matches the two-band ink measurement from the original v1.4.85 investigation
  (7-25% and 34-77% of card height) - the second band wasn't a separate
  decorative watermark as first assumed, it was the digit's own lower loop
  being clipped. v1.4.92 widens/heightens both ROI paths to 0.48×0.42, on top
  of the existing 4% top inset. Not yet Evaluate-verified.
- **Waste truth can be Six on a Jack (and Clubs on a Spade).** Pixel-checked this
  session: `190130` and `143855` playable waste are Jacks labeled Six; `032046`
  is J♠ labeled Clubs (relabeled). `205220` is the same JS-labeled-JC leftover.
  Relabel after a crop; do not Six-steal those back.
- **`GameStateDetector.tableauRunConsistencyDiagnostics`** (added v1.4.89, diagnostic-
  only) — Klondike invariant check: a tableau's exposed face-up run must be one
  continuous alternating-color, descending-rank sequence. Only compares cards at
  physically-adjacent column positions (i, i+1); an earlier draft compacted the
  column to just known/non-inferred cards first and compared consecutive
  survivors, which silently treated cards separated by an inferred slot as
  adjacent and produced nonsense violations - caught by re-validating offline
  before shipping, not by any test. Re-validated against the full current golden
  set after the fix: 1 broken adjacency in 1543 truth-data adjacent pairs (the
  known duplicate-Six-of-Spades labeling defect, not a real exception), and
  every one of 214 adjacencies flagged against engine-recognized reads
  corresponded to a real rank/suit mismatch on at least one side (100%
  precision on this set). Logged only (`tableau$col.runConsistency=broken:...`)
  - does not reject frames or mutate state yet.
  **Visibility correction (v1.4.89 Evaluate run)**: this diagnostic writes to
  `DetectionResult.diagnostics`, which the Golden-truth Evaluate flow never
  reads (`GoldenTruthEvaluator` only logs `result.summary()` and
  `mismatchTraceBlock()` - confirmed zero `runConsistency` occurrences in a
  full Evaluate analysis.log even though nothing crashed and the run was
  otherwise clean). It only reaches `analysis.log` via the live-play path
  (`AnalysisPipeline`'s outcome logger dumps `detection.diagnostics` as
  `diag: ...` lines on every ARROW/NO_MOVE outcome change). So checking this
  signal needs an actual play session with `diag: tableau` lines pulled
  afterward, never an Evaluate-only pull.

## Live-play pipeline performance (arrow latency / flicker)

User complaint (v1.4.93 round): the arrow is slow to appear and sometimes
flickers to a wrong move before settling on the correct one, badly enough to
cost games on time. Concurrency/perf changes are the highest-uncertainty risk
category (see "Validation discipline" above) - no Python replica applies,
Evaluate doesn't measure it either (Evaluate is single-shot per golden image,
no frame-to-frame timing or stickiness in play), so this can only be reasoned
about from the code and confirmed by watching real play afterward.

**Architecture facts gathered before touching anything:**
- `ScreenCaptureController.intervalMs` defaults to 750ms internally, but
  `AssistantSettings.captureIntervalMs` (what the app actually starts capture
  with) defaults to 200ms - and `AssistantPreferences`' migration logic
  actively resets a persisted 750 or 300 back to 200, so the interval has
  already been tuned down twice in earlier rounds. Not touched again here for
  lack of new evidence it's still the bottleneck.
- `AnalysisPipeline.onFrame` hands frames to a single-threaded
  `analysisExecutor` with keep-only-latest semantics (`pendingFrame.getAndSet`
  drops the previous unprocessed frame) - correct for freshness, but it means
  total throughput is gated entirely by one frame's `detect()`+select time;
  a slow frame doesn't just delay itself, it delays the *next* frame picked
  up too.
- `GameStateDetector.detect()` itself is already well concurrent (see
  "Recognition pipeline architecture" above): foundations + all 7 tableau
  columns run on a dedicated pool, submitted *before* the sequential waste
  template/fusion work so that work overlaps the pool instead of blocking it.
  Waste OCR runs on its own single-thread executor, overlapped the same way,
  and is only `.get()`'d at the very end. This is already deliberate, recent
  work, not a naive sequential pipeline.
- Within that overlap, `CardRecognizer.attemptWasteRankOcr` was the one
  documented-but-unaddressed cost outlier: its own comment already says
  trying every region x whole/corner combination "is what made waste
  recognition the dominant per-frame cost" - up to ~10-12 sequential blocking
  ML Kit calls (500ms timeout each) when nothing hits the confidence early-
  exit. The 132126/132140 Evaluate log (see the shadow-band section above)
  is real evidence this isn't hypothetical: all 10 attempts came back
  `ocr=miss:empty` on that sample, meaning the full worst-case cost was paid.

**Changes made this round:**
- **`CardRecognizer.WASTE_OCR_EMPTY_CIRCUIT_BREAKER = 4`** (new): aborts
  `attemptWasteRankOcr`'s region search after 4 consecutive genuine
  `ocr=miss:empty` results (ML Kit finding literally no text, not just an
  ambiguous read) instead of exhausting all ~10-12 attempts. Only shortens
  the search on frames that were already headed for no OCR result - cannot
  change which answer wins on a frame where OCR does find something, so it
  should be latency-only and accuracy-neutral. Logs
  `ocr-circuit-breaker:N-empty` in the trace when it fires.
- **`SuggestionStickiness.apply`'s new `visualChangeStreak` parameter**
  (default 1, so all pre-existing call sites and tests are unaffected):
  distinguishes "this is the first frame since the board was static" (streak
  1 - the fast, intended "a move just landed, show it now" case) from "the
  board is still visually changing frame over frame" (streak 2+ - most
  likely mid-animation, e.g. a card-slide). The immediate-adopt bypass that
  used to fire on *every* visually-changed frame regardless of streak now
  only fires on streak 1; streak 2+ falls through to the same
  two-agreeing-reads confirmation the static-board path already used. This
  is the direct fix for "flickers to a wrong move before settling": that
  pattern traces to `boardVisuallyChanged` staying true for the whole span of
  an animation (each frame pixel-differs from the last one), and the old
  code treating every one of those frames as an independent "trust this
  instantly" signal with zero cross-frame confirmation as long as pixels
  kept moving. A read that matches whatever is *already displayed* is still
  always immediate regardless of streak - only a *differing* candidate during
  an ongoing visual change is held back. `AnalysisPipeline` tracks the streak
  itself (`visualChangeStreak` field, incremented when `boardVisuallyChanged`
  is true, reset to 0 otherwise and on session boundaries) and passes it
  through. Covered by four new `SuggestionStickinessTest` cases exercising
  streak 1 vs 2+, agreement-is-still-immediate, and two-agreeing-reads
  adoption.

**Not changed, and why:** the `fastUpdateAfterMove` and inner "stabilizing"
confidence floors (0.57 and 0.48 by default) were considered but left alone -
lowering/raising them is exactly the kind of magic-number tuning that needs
real device confidence-distribution data to justify, which isn't available
here, and the two floors overlap (raising one alone likely wouldn't reduce
anything, since frames that stop qualifying for the higher one would just
fall through to the still-permissive lower one). The streak fix operates at
the mechanism level instead, so it doesn't need that data.

**Not yet device-verified.** Both changes are reasoned from the code, not
measured. Watch real play for: (1) whether the arrow still flickers between
different moves during/right after a card-slide, (2) whether frames with a
hard-to-read waste card visibly feel less laggy, (3) whether legitimate rapid
back-to-back moves still feel responsive (the streak reset on any static
frame should preserve this, but hasn't been observed on-device). If arrow
responsiveness gets worse instead of better, this whole round is the first
thing to revert - user explicitly authorized that risk given how directly
this blocks winning games on time.

**Round 2 (v1.4.94, same session): the real dominant cost, found from
a real pulled `analysis.log`.** User feedback after round 1: "feels the same
as before." The log proved why - `AnalysisPipeline.boardFingerprint`'s
single rolling hash (mentioned above, already widened 5→4 bits/channel in an
earlier round) was *still* not tolerant enough: the same recognized move
("Draw from stock") got re-logged with a different confidence value on
almost every frame across a ~20s idle span where nothing on the board
changed. Since a hash has zero tolerance for even one sample flipping its
quantization bucket, and the fingerprint self-samples ~3640 points (54×60 +
40×10) every frame from a real MediaProjection capture (which has some
amount of inherent per-frame pixel jitter even on static content - GPU
compositor rounding, dithering), the "unchanged" cache path was essentially
never engaging. Every single frame paid a full `detect()` call (observed
130ms-1800ms in the log, worse during a new-deal animation) regardless of
whether the board had actually moved - this is almost certainly the
dominant contributor to "still slow," bigger than either round-1 fix.
Changed `boardFingerprint`/`fingerprintRegion` from "hash must match
exactly" to "count of differing quantized samples must stay within
`FINGERPRINT_NOISE_TOLERANCE` (8, out of ~3640)" - stores the last frame's
sample array instead of a single hash and compares element-wise. Same
sample count/positions/quantization as before, so no new accuracy exposure;
purely changes the equality test from exact to near-exact. Also confirmed
from the same log that the round-1 OCR circuit breaker did fire correctly
(4 times, capped a would-be 10-attempt search) - it just wasn't enough on
its own given this larger issue sitting underneath it. Not yet
device-verified - needs another real play session + pulled log to check
whether `detect()` calls are now mostly skipped on a static board (look for
short/absent `timing: ... detect=` gaps between identical logged moves) and
whether the arrow now updates closer to the true capture interval.

**Round 2 reverted in round 3 (v1.4.95, same session) - real regression, not
just "unverified."** User report right after playing on v1.4.94: "now it got
much worse... the arrow sometimes completely disappears... never happened
before the last change." A pulled log confirmed a concrete failure mode:
an 80-second stretch logged the exact same move ("Draw from stock") with
*exactly* the same confidence (0.60) at both ends, while `known` (known
face-up count) quietly drifted from 2 to 10 in between with zero intervening
log entries - proof the pipeline was reusing one stale cached `detect()`
result for 80 seconds straight while the real board kept changing underneath
it. The noise-tolerance fix's failure mode is exactly this: a real but small,
same-color visual change (e.g. a waste card swap where only the ink glyph
differs and the coarse 54×60/40×10 sample grid mostly lands on white
background) can affect fewer quantized samples than any tolerance loose
enough to also absorb genuine capture noise - so it can silently miss a real
change and keep serving stale state, which is a correctness regression, not
just "still slow." Reverted `boardFingerprint`/`fingerprintRegion` back to
the exact-hash match (see the comment now in that function for the full
history). The OCR circuit breaker and the `visualChangeStreak` stickiness
fix from round 1 are unaffected by this revert and stay in place - neither
showed this failure mode, and the mechanism that broke (frame-level
caching) is unrelated to either of them.

**Lesson for next attempt at the real "always slow" problem:** the
underlying finding from round 2 (the hash has zero noise tolerance and the
cache was barely ever engaging) is still true and still worth fixing - just
not with a flat sample-count tolerance across a large, coarse grid. A safer
angle would need either a much finer-grained per-region check (so a small
localized change can't hide under a global tolerance budget) or actual
device measurement of what a real "nothing changed" vs "waste card swapped"
sample-diff distribution looks like, before picking a threshold - exactly
the kind of broad-evidence-before-a-threshold-change discipline the
geometry-constant warnings elsewhere in this file already describe.

**v1.4.95 device-verified positive: real fix, not just reverted-to-neutral.**
User report after playing on v1.4.95: latency is back to the pre-round-2
baseline (expected - it's a straight revert) and "most of the flickering
gone, but it still appears sometimes." That confirms the `visualChangeStreak`
stickiness mechanism from the v1.4.93 round is a genuine, working
improvement, not just an unverified guess - it survived a real play session
without the disappearing-arrow regression and measurably reduced flicker.
The residual flicker is expected, not a new bug: the fix only protects
frames *after* the first one in a visual-change streak (requiring two
agreeing reads before adopting a *different* move mid-animation) - the very
first post-move frame still adopts immediately with zero confirmation, by
design, to keep the arrow responsive right after a real move. If that first
frame's own read is wrong, one visible flicker-then-correct is still
possible before the streak protection kicks in on the next frame. Tightening
that further (e.g. raising `fastUpdateAfterMove`'s confidence floor) is the
next lever, but - per the round-1 note above - not something to guess a new
number for without real confidence-distribution data; needs a log from a
session where the residual flicker was actually observed to know if it's
this mechanism or something else.

**Round 4 (v1.4.102, later session): OS thread scheduling priority - a new
lever, distinct from every round above.** Rounds 1-3 all changed *what* gets
computed or *when* a result is trusted; this round changes only how the OS
schedules the threads that were already there, with zero logic change - the
lowest-risk lever available for this problem, since it can't introduce the
kind of correctness regression round 2 did (no shared mutable state touched,
no caching/staleness surface at all). Checked first: none of the pipeline's
executors (`AnalysisPipeline.analysisExecutor`/`rejectionExecutor`,
`GameStateDetector.columnExecutor`/`wasteOcrExecutor`) set thread priority -
plain `Executors.newSingleThreadExecutor()`/`newFixedThreadPool()`, which
run at Android's default scheduling tier, the same tier as every other
thread on the device including Solitaire Smash's own rendering/game-logic
threads it's competing against for CPU. Process-level priority was already
right (`CaptureService.startForeground()` already declares
`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, giving the whole app process an
elevated OOM/scheduling tier) - only thread-level priority within that
process was left at default.

Added `vision/PriorityThreadFactory.kt`: wraps a `ThreadFactory` that calls
`android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)`
(-2) as the first action of each worker thread, before it enters the pool's
task loop - `Process.setThreadPriority` sets the real Linux nice value the
kernel scheduler uses (unlike `Thread.setPriority`, which Android mostly
ignores), and since `Executors`' pool threads are long-lived and reused for
every future task, setting it once when the thread is created covers the
thread's whole life, not just the first task. Wired into the two hot-path
executors that gate arrow latency: `analysisExecutor` (the single thread
every frame funnels through) and `GameStateDetector`'s `columnExecutor`/
`wasteOcrExecutor` (awaited synchronously inside every `detect()` call, so
on the same latency-critical path despite living in a different class).
Deliberately left `rejectionExecutor` (background disk I/O for error-capture
and move-history) and `AnalysisFileLogger`'s executor at default priority -
neither gates arrow latency, and elevating them would only add contention
against the threads that do.

Picked `THREAD_PRIORITY_FOREGROUND` (-2) over the more aggressive
`THREAD_PRIORITY_URGENT_DISPLAY` (-8, the level Android itself reserves for
actual frame-rendering/compositor threads) on purpose: the goal is winning
scheduling races against other apps/system threads under contention, not
starving Solitaire Smash's own rendering thread, which would make the game
itself feel worse even if our arrow got faster - the same
don't-reach-for-the-extreme-setting-without-evidence discipline the
geometry-constant and fingerprint-tolerance warnings elsewhere in this file
describe. `THREAD_PRIORITY_URGENT_DISPLAY` is the next lever to try if
`FOREGROUND` doesn't move the needle enough on a real pull.

**Not yet device-verified**, and worth being honest about the ceiling here:
if the phone is otherwise idle while the user is playing (nothing else
contending for CPU), thread priority only matters under contention, so the
realistic upper bound on what this buys is smaller than the architectural
levers in rounds 1-3. The existing `timing: ... detect=` log lines (same
ones round 2's investigation used) are how to confirm whether this actually
moves per-frame latency - compare `detect=` durations across a real play
session before and after. The other lever identified but not attempted this
round: parallelizing waste OCR itself. `CardRecognizer.attemptWasteRankOcr`'s
own comment already says it's "what made waste recognition the dominant
per-frame cost," and it still runs strictly sequentially region-by-region on
a single-thread executor, unlike the tableau columns which were already
parallelized in an earlier round - a genuinely higher-ceiling lever than
thread priority, but a concurrency change (same risk category as rounds 2-3
above), not attempted here without a specific plan for it yet.

**v1.4.102 partial device evidence: directionally positive, not isolated.**
User pulled a real `analysis.log` on v1.4.102 (82 confirmed frames) and a
second log from a pre-v1.4.100 build (67 frames, confirmed older by zero
`statusBarVisible` occurrences - that signal didn't exist yet) for
comparison. `detect=` timing: median 338→308ms (-9%), mean 486→356ms
(-27%), p90 1085→611ms (-44%), max 2984→1119ms (-62%), frames ≥1000ms
13%→2%. The tail improved far more than the median, which is the right
shape for a scheduling-priority fix (it should matter most exactly when
something else is contending for CPU). Two honest caveats: different play
sessions with different board activity, not a same-board A/B test; and the
older log predates v1.4.100/101 too, so this reflects several rounds
stacked together, not thread-priority in isolation. Keep the v1.4.102 log
as the new reference point for the next comparison rather than the older
one, to better isolate what the *next* change does.

**Round 5 (v1.4.103, same later session): parallelize `attemptWasteRankOcr`
itself - the higher-ceiling lever round 4 identified but didn't attempt.**
User asked to push on this directly. Read `RankCornerOcr.attempt()` first
because it matters more here than for any other lever in this file: it's
`@Synchronized` **per-instance**, and `CardRecognizer` held exactly one
shared `rankCornerOcr` instance for every OCR call (tableau/foundation
tiebreak *and* waste). Naively submitting waste-OCR region attempts to a
multi-thread pool while they all still call that one instance would not
have parallelized anything - they'd serialize on its lock with pure thread-
scheduling overhead added on top, a real trap for this specific lever that
isn't obvious from the outside. The fix is instances, not threads: ML Kit's
restriction is on concurrent calls to *one* client, not on running multiple
independent clients at once, so `CardRecognizer` now also lazily builds a
small dedicated pool of separate `RankCornerOcr` instances
(`wasteOcrProbePool`, size `WASTE_OCR_PROBE_PARALLELISM = 2`) purely for
this path - `rankCornerOcr` itself is untouched, so the rare tableau/
foundation OCR tiebreak carries zero risk from this change.

`attemptWasteRankOcr` is now two phases:
1. **Unchanged fast path**: region 0's "whole" attempt runs exactly as
   before, fully sequential, zero added cost. This is the documented common
   case (a clean short parse: "K", "10", "A", a digit) and it exits here at
   the same cost as every prior version.
2. **New parallel-wave fallback**, only reached when phase 1 didn't
   resolve: every remaining whole/corner probe (same priority order the old
   loop used) is dispatched in waves of `WASTE_OCR_PROBE_PARALLELISM` via a
   new `wasteOcrProbeExecutor` (also `PriorityThreadFactory`-elevated,
   separate from `GameStateDetector`'s `wasteOcrExecutor` so the two pools
   can't self-contend - the outer call already occupies `wasteOcrExecutor`'s
   one thread while it blocks on the probe pool's futures, so reusing the
   same pool for both would have deadlocked/starved instead of adding real
   parallelism, a second easy-to-miss trap for this lever). Selection logic
   (`considerWasteOcrAttempt`'s strictly-higher-confidence-wins rule) is
   untouched, so extra probes can only find the same answer faster, never a
   different one.

Two deliberate, documented semantic trade-offs from batching (both compared
in code comments against the old strictly-sequential version): a wave can
pay for its second probe even when the first already resolved things
(the old code could skip a region's corner attempt entirely on early exit;
batching can't), and the circuit breaker can only stop *between* waves, not
mid-wave, so the true worst case can run up to
`WASTE_OCR_PROBE_PARALLELISM - 1` more ML Kit calls than before. In
exchange, wall-clock cost for exactly the case the circuit breaker itself
was built for (several weak/empty regions in a row) drops by roughly a
factor of `WASTE_OCR_PROBE_PARALLELISM`, since those calls now overlap
instead of queuing.

Parallelism degree deliberately kept at 2, not higher: this pool competes
for CPU with `GameStateDetector`'s `columnExecutor` (already sized to
`availableProcessors()`) on the same frame, and there's no device data yet
on how much headroom actually exists - same
don't-reach-for-the-extreme-setting-without-evidence discipline as
`THREAD_PRIORITY_FOREGROUND` vs `URGENT_DISPLAY` in round 4. Falls back to
the original fully-sequential search (same probe list, one at a time) if
the pool failed to initialize, so a same-device OCR-unavailable case still
works, just without the latency win.

**Not yet device-verified** - concurrency changes can't be validated via
the Python-replica technique at all (nothing to numerically compare), so
this is reasoned from the code with the same care the fingerprint-caching
regression should have gotten, not confirmed by a real pull. What to check
on the next pull: `timing: ... detect=` distribution (expect the tail -
p90/max - to move again, similar to round 4's shape, on frames that
actually needed OCR); no `overlapped=` regression in the trace lines; and
critically, that recognized waste ranks on frames needing multiple OCR
probes still make sense (the golden-truth Evaluate flow doesn't exercise
`attemptWasteRankOcr`'s multi-region fallback the same way live play with a
hard-to-read waste card does, so Evaluate coming back clean would not by
itself confirm this path is correct - watch a real game's `moves.log`/
`analysis.log` for waste-rank sanity specifically).

## Move history capture (v1.4.97)

User request: a way to review a whole finished (including abandoned) game
afterward and check whether a different early move would have won, since
the assistant only ever scores the immediate position, not whether the
current line is winnable. Not a recognition or performance change - a new
opt-in feature.

- **`vision/MoveHistoryStore`**: one subfolder per deal at
  `files/move_history/<deal-timestamp>/`, one `NNNN.png` (screenshot) +
  `NNNN.txt` (plain-text board state) pair per confirmed move, `0000` being
  the opening deal. `describeCard` deliberately does not reuse `Card.toString()`
  - its one-letter rank abbreviation collides (Ten/Two/Three all start with
  'T'), fine for a debug log line but not for a record meant to be read back
  accurately later.
- Hooked into `AnalysisPipeline.handleDetection` at both places
  `lastStableState` is reassigned to a genuinely new `GameState` (piggybacks
  on the existing `recentStates.lastOrNull() != state` dedup check, so it's
  one save per real move, immune to confidence-only noise since `GameState`
  equality doesn't include confidence).
- New deal detection (`DealBoundary.newGameReason`, already used to reset
  rejected-move history) also starts a fresh `MoveHistoryStore` subfolder.
- Runs off the hot path on purpose: `recordMoveHistoryAsync` copies the
  bitmap synchronously (cheap) and does the actual PNG encode + disk write
  on `rejectionExecutor` (the same background executor recognition-error
  capture already uses), not on `analysisExecutor` - encoding a full board
  screenshot inline on the single thread that gates arrow latency would
  reintroduce exactly the per-frame cost the "Live-play pipeline
  performance" section above spent multiple rounds cutting down.
- New settings toggle "Save move history" (off by default).
- The pre-existing "Save debug frames" toggle (`CaptureService.saveDebugFrame`,
  wrote the latest raw capture to `cacheDir/frames/latest.png` on every frame,
  overwritten each time) was **removed entirely** (v1.4.98, user request) -
  no move awareness, nothing kept over time, superseded by this feature for
  the user's actual need. Deleted the setting, its DataStore key, the Settings
  toggle, and the write path; nothing else in the codebase read that file back.

**v1.4.98: first real-device test surfaced two bugs, both from the same root
cause, both fixed.** User pulled one real (short) game and reported: (1) a
new `move_history` subfolder started mid-game, more than once; (2) a lot of
near-duplicate images. Pixel/text-checked all 5 folders plus one unrelated
orphan from a prior game: one real opening deal produced **4 separate
folders within 13 seconds**, several holding two saves that differed by
exactly one card (e.g. a column's last card read as `Four_Diamonds` then
corrected to `Six_Hearts` a moment later) - a misread self-correcting
mid-animation, not a real move.

Root cause: both `maybeResetRejectionsForNewDeal` (which starts a new
`MoveHistoryStore` session) and the move-history save were being called from
*two* places in `handleDetection` - the fully-confirmed branch (`stableHits
>= 2` or a high-confidence fast path) **and** the "still stabilizing,
confidence >= 0.48" best-effort branch that exists only to keep the live
arrow responsive while a frame is still weak. A multi-second deal animation
spends most of its time in that second, weaker branch: cards are still
sliding in, so hidden-card count and the set of known face-up cards jump
around wildly frame to frame - exactly the kind of jump `DealBoundary`'s
heuristics (`hidden-jump`, `known-set-turnover`) are designed to read as "a
different game," and exactly the kind of frame-to-frame instability that
produces a "new" `GameState` on every single frame.

Fix: removed the `maybeResetRejectionsForNewDeal(state)` call and the
move-history save from the "still stabilizing" branch. Both now fire only
from the fully-confirmed branch. `lastStableState`/`recentStates` still
update eagerly in the weak branch (unchanged) - `cancelCurrentHint()` and
`showBestSuggestion`'s `avoidStates` need whatever is actually on screen
regardless of confirmation tier, so removing those would have broken hint
rejection. This is a **general correctness fix**, not move-history-specific:
`maybeResetRejectionsForNewDeal` firing repeatedly during a deal animation
was silently clearing the user's rejected-move history multiple times per
deal even before this feature existed - the bug was always there, this
feature just made it visible.

Residual risk: `reliableFirstHit` (confidence >= 0.82, knownFaceUp >= 4) and
`fastUpdateAfterMove` in the *outer* gate can still occasionally route a
still-mid-animation frame straight to the confirmed branch if it happens to
read cleanly for one frame. Not fixed here for lack of evidence it's still
a real problem after this change - the observed folders all matched the
weak-branch pattern. If the next real game still shows fragmentation, the
next lever is debouncing `DealBoundary` itself (require the same
`newGameReason` on 2 consecutive confirmed calls), not widening this fix
further blind.

Not yet re-verified - needs the next played game to confirm one deal now
produces exactly one folder with no near-duplicate saves.

**v1.4.98 pulled and checked: fix worked for its main target, one residual
edge found and fixed in v1.4.99.** User played a real game and pushed
`files/move_history`. Findings from reading all 5 folders + text summaries:
- The two real-game folders (110 moves, 40 moves) are clean - the 40-move
  one has only 2 non-move transitions total (both a waste slot briefly
  reading `?unread` between a draw and the OCR resolving it, not noise).
  The 110-move one's "duplicate-looking" transitions are mostly genuine
  consecutive waste draws (`waste: Jack_Spades -> waste: Queen_Clubs` is a
  real new card, not a misread); the real noise left is a handful of
  flip-flopping reads on one long, deep tableau cascade - a known,
  separately-documented recognition-accuracy limitation (see "long-cascade
  compounding" below), not something this hook's timing can fix.
- Fragmentation is real but now confined to the opening ~3 seconds of a
  session (3 tiny folders before the real 110-move folder takes over),
  down from spanning an entire short game before v1.4.98. Root cause:
  `maybeResetRejectionsForNewDeal` is only called from the confirmed branch
  now, but *even a confirmed read* can land on a still-mid-deal frame, and
  two separate confirmed reads a moment apart can each look like a big
  enough jump to `DealBoundary` to count as "a new game" on their own.
- **v1.4.99** requires `hidden-jump`/`known-set-turnover` (the reasons that
  actually fired repeatedly mid-deal - hidden count and the known-card set
  both swing wildly while cards are still being revealed) on 2 consecutive
  confirmed calls before acting, same idea as `visualChangeStreak`
  elsewhere in this file. Deliberately does NOT gate `fresh-layout` or
  `foundation-drop` the same way: `fresh-layout` only ever fires once by
  construction (`DealBoundary.newGameReason` returns null again the moment
  `previous` also matches a fresh layout), so requiring it twice would mean
  it could never fire at all; `foundation-drop` needs an existing
  foundation with 2+ cards, which is structurally impossible during the
  opening deal since foundations start and stay empty until the player's
  first real move. Not yet re-verified - needs the next played game.

**v1.4.100: non-board screens (in-game dialog, post-game results screen)
were being captured as if they were live board states - root cause found
and fixed via `SmashPlayScreenGate.statusBarVisible`.** Analyzing the full
134-move `20260831_150825` folder (see below) to answer the user's actual
question - "could a different move have avoided getting stuck" - surfaced
this: `0131.png` turned out to be the app's own "Finish Game?" confirm
dialog (triggered by the player tapping End) dimming the real board
underneath, and `0133.png` (the recorded final move) turned out to be a
completely different screen, the post-game tournament results/leaderboard
("Results pending", unrelated card-shaped UI). Neither is caught by the
existing `SmashPlayScreenGate` lobby/footer checks: the dialog still shows
an undimmed End/Undo/Rules footer (only the mid-screen is covered), and the
results screen isn't the lobby either - so both got recognized as bogus
"confirmed" board states, corrupting the tail of the move-history record
with 4 garbage moves (`0000`, `0131`, `0132`, `0133` - `0000` was also
garbage, a leftover menu-transition frame from before the real deal even
started). Root cause: live play runs the app in immersive/fullscreen mode,
but both the dialog and the results screen release it and expose the real
Android status bar. Pixel-checked across the entire 134-frame folder plus
every existing `SmashPlayScreenGateTest` fixture (5 golden samples +
`device_5.png` + `lobby_home.jpg`): the top 2% strip's avgLuma is a flat,
constant 0.390 on every one of 130 real live-play frames (5 different
golden captures too) and 0.417 on the lobby screen, but only 0.124-0.189 on
the 4 garbage frames - a clean, wide (0.10+ either side), zero-exception
split on every fixture available. Added `SmashPlayScreenGate.statusBarVisible`
(threshold 0.29, sampled in raw bitmap coordinates rather than board-relative
since board detection itself is unreliable on these off-board screens) and
required `!statusBarVisible` in `GameStateDetector.isLivePlayScreen` - this
gate feeds both the move-history save path and the live overlay-arrow
visibility, so this was also a latent live-play bug (a bogus arrow could
theoretically render over a "Finish Game?" dialog), not just a move-history
cosmetic one. Two new fixtures (`dialog_finish_game.png`,
`results_pending.png`, both pulled from the real `20260831_150825` folder)
plus two new `SmashPlayScreenGateTest` cases lock this in. Only validated
against this one device/theme's pixel values - not yet checked against a
broader device sample, unlike most other thresholds in this file.

**v1.4.99 device-verified: fully fixed.** Next real-game pull (commit
`2c34db6`) produced a single clean folder `20260831_150825/` with 134 moves,
zero fragmentation, zero exact-duplicate saves (checked programmatically).
Move-history save timing is done - no further rounds needed on this bucket
unless a new real-game pull shows regression.

**Move-history record surfaced a live example of the already-tracked
waste-top-identity bug (not a move-history timing bug).** User reviewed the
134-move folder and flagged `0016.txt`/`0016.png` vs `0017.txt`/`0017.png`:
the two `.txt` summaries are byte-identical except the waste line
(`Ace_Diamonds` -> `Six_Clubs`), and `0016.png` visually shows the waste fan
mid-reveal (a "10" and a partially-covered "A" visible, Score 180). The
pipeline's own confirmation gate (`stableHits>=2`) passed on move `0016`
before self-correcting one entry later at `0017` - so this is not a
move-history-hook-timing bug like the two fixed above (the hook only fires
from the fully-confirmed branch, and it did): the underlying CV read itself
was confident/stable enough to clear that bar on a covered mid-fan card
(the Ace), exactly the mechanism already documented under "Active work:
waste top identity" above (`RankCornerOcr`/`ocrRankOverride` latching a
peeking neighbor instead of the true front card). No new code lever
identified beyond what's already tracked there (the v1.4.92 ROI
enlargement, not yet Evaluate-verified, is still the live candidate fix).
Logged here as corroborating real-play evidence, not a new bug.

**Post-mortem of the `20260831_150825` stuck game: corrects a wrong claim
from an earlier compaction summary, and finds one concrete missed move.**
An earlier session summary described the game's final state as a "13-card
K♣-to-2♥ stuck cascade in tableau1" - that was wrong, sourced from
misreading the garbage frames fixed above (`0132`/`0133`, the results
screen), not the real board. The real final board (moves 123-130, fully
static except stock/waste cycling - the actual stuck position) has
tableau1 at only 3 cards (`King_Clubs Queen_Diamonds Jack_Clubs`).
Foundations: Spades=2, Hearts=3, Clubs=2, Diamonds=0 (empty). Pixel-checked
`0126.png`: tableau2's exposed top card is unambiguously **Four of
Hearts** (real heart pip, confirmed by crop), but the recognizer tagged it
`Four_Hearts~` (suit-ambiguous). Hearts foundation sat at Three the entire
static stretch, so this was a real, legal, safe foundation move sitting
exposed and unplayed - very likely never suggested because, per this
file's own documented tradeoff, a suit-ambiguous flag suppresses the
foundation arrow. This is the first known instance of that tradeoff
actually costing a move during real play, not just a golden-set accuracy
point - previous evidence for suit ambiguity was all Evaluate-only. Not
a fix (per "Don't spend another round on C↔S/suit-ambiguity scoring" -
this is a red H/D pair, not the parked black C/S one, but the same
general tradeoff applies and touching it risks the same net-regression
history), just documented corroborating evidence. Chaining one legal move
forward from there (Five_Clubs -> Six_Hearts becomes available once
Four_Hearts leaves) does not obviously cascade into a full win - the board
still looks jammed. Whether an earlier, different move could have won is
not something this log can answer rigorously: `MoveHistoryStore.record`
only saves the single current waste-top card (`GameState.waste` is a
1-element window, not the full pile), so the original stock draw order
can't be reconstructed from this log format at all, and the fully-built
alternating tableau2 run's own suit reads flip-flopped between the two
adjacent garbage frames `0132`/`0133` (same ranks, opposite suits both
internally consistent) - real evidence the suit reads on a long cascade
run are not reliable enough to trust for a retroactive full-deck replay,
independent of the screen-gating bug. A genuine "replay and try alternate
lines" feature would need the recorder to capture the full waste/stock
order and the discrete move list, not periodic confirmed snapshots - not
attempted here for lack of a concrete request to build it.

**v1.4.101: discrete move-list logging (`moves.log`), the first half of the
"full waste/stock order + discrete move list" ask above.** User asked to go
ahead and build it. Scoped down from the full ask on purpose, and the scope
cut is the important part to remember:

- **What got built**: `game/MoveTransitionDescriber.kt` (pure Kotlin, no
  Android/vision deps, unit-tested in `MoveTransitionDescriberTest.kt` with
  11 cases) diffs two consecutive confirmed `GameState`s into one line -
  `"Four_Hearts: tableau0 -> foundation1"`, `"draw: Six_Clubs"`,
  `"reveal: tableau1 -> King_Hearts"`, a multi-card run as
  `"Six_Clubs (+1 more): tableau0 -> tableau1"` (reports the count, does not
  guess which column an ambiguous run's *buried* cards came from), or a
  generic `"tableau$col changed"`/`"state changed (see snapshot)"` fallback
  when nothing above resolves cleanly - honesty over a wrong specific claim.
  `MoveHistoryStore.record()` now takes the previous recorded `GameState`
  (tracked in `AnalysisPipeline` as `lastRecordedMoveHistoryState`, captured
  synchronously on the caller's thread before the async save so a burst of
  confirmed frames can't race two saves into using a stale "previous") and
  appends `"$moveIndex $description\n"` to a cumulative `moves.log` in the
  session folder, alongside the existing NNNN.png/NNNN.txt pairs (unchanged).
  Reset to null in `clear()` and whenever `moveHistoryStore.newSession()`
  fires, so a new deal's first line is always "deal: opening layout", never
  a stale diff against the previous game.
- **What did NOT get built, and why**: the full waste/stock draw order.
  `GameState.waste` only ever holds the single front/playable fan card - the
  recognizer never tries to read the other 1-2 covered cards in a draw-3 fan
  as separate identified `Card`s (that region is exactly the source of the
  already-documented waste-top-identity misreads, e.g. the 0016/0017 case
  above). Diffing consecutive waste-tops only recovers the front card of
  each *reveal*, not the full pile - two-thirds of the true stock order (the
  cards that get buried under later draws without ever becoming the front
  card) is fundamentally unrecoverable from what this app currently
  recognizes, regardless of how the recorder is built. Actually reading the
  fan's buried cards would be a real, separate, higher-risk CV feature (new
  recognition calls on already-unreliable regions, no golden-truth coverage
  for it) - out of scope for a logging change, not attempted here.
- **Bookkeeping correctness worth noting for future edits to this file**:
  the diff logic runs in two size-ordered phases per tableau column - phase
  1 handles every column that *grew* (via a clean prefix-extension) first
  and records the moved card's id in `explainedAsDestination`; phase 2 then
  handles every column that *shrank or stayed the same size*, and only
  reports a loss/generic-change line when the lost card's id isn't already
  in that set. Getting this ordering backwards was a real bug caught in my
  own unit-test tracing before shipping (an earlier draft processed columns
  in a single pass in index order, so a tableau-to-tableau move from column
  0 to column 1 would double-report: "tableau0 lost 1 card" *and* the
  correct "Five_Clubs: tableau0 -> tableau1" line, because column 0's loss
  was evaluated before column 1's gain had a chance to populate
  `explainedAsDestination`). Each column is classified into growth-phase-1
  XOR everything-else-phase-2 by a strict size comparison so the two phases
  never both describe the same column.
- **Not yet device-verified.** This only touches the move-history feature
  (default off), not recognition or live-play arrow logic, so the risk
  profile is low, but it hasn't been checked against a real pulled game yet
  - next real pull with "Save move history" on should be spot-checked for
  whether `moves.log` reads as a sane chronological account of the game
  (including whether the fallback lines fire more than expected, which
  would mean the diff heuristics need loosening for some case not covered
  by the 11 unit tests).

## Solver heuristics

`solver/MoveSelector.kt` is a bounded one-ply scorer with light one-move
lookahead, not an exhaustive solver — it scores every legal move and picks the
highest, so changes here are prioritization tweaks, not new capabilities.
There is no on-device or Evaluate-based validation loop for solver quality
(Evaluate measures CV recognition accuracy only); these are reasoned from the
code and existing unit tests (`solver/MoveSelectorTest.kt`), not device-verified
- watch actual suggested moves during play after updating.

- Hidden-card priority, empty-column King-only sequencing, and "safe" Ace/low-
  card foundation restraint (Baker-style) were all already implemented before
  this note was written - see `scoreTransition`'s `reveal+`/`deep-col` bonuses,
  `Card.canStackOnTableau`'s `target == null -> rank == King` rule, and
  `KlondikeRules.isSafeFoundationMove`.
- **v1.4.89**: `isTableauUsefulLowCard`'s "hold this card back as a tableau
  bridge" restraint now also covers Three (previously Two only), and is gated
  on `before.hiddenTableauCount() > 0` - once every tableau card is already
  face-up there's nothing left to expose by preserving a bridge, so the
  restraint no longer earns its keep and the card goes to foundation at full
  score instead of the deferred one. Checked against every existing
  `MoveSelectorTest` case by hand (none of them have a genuine bridge Two/Three
  with zero hidden tableau cards, so none flip) since gradle can't run here.

## Current state (as of v1.4.89 / versionCode 160, Evaluate-verified)

**v1.4.89 Evaluate: 5347/5472 (98%).** Golden set grew to 155 samples/5472
slots (4 new files) since v1.4.84, so this isn't a clean same-denominator
diff vs the row below — rank 50 (was 52), suit 83 (was 85), occupancy 24,
missing 4, two runs back-to-back gave identical numbers (no flake, no
crash). `230705` and `080754` (the two waste no-OCR/OCR-override targets)
both cleared. `132126`/`132140` did not - see the shadow-band correction
above; `230705` clearing is credited cautiously since a correct read leaves
no trace to confirm the mechanism.

| version | accuracy | rank | suit | C→S / S→C |
|---|---|---|---|---|
| v1.4.80 / 151 | 5179/5317 | 62 | 87 | 24 / 14 |
| v1.4.81 / 152 | 5182/5317 (+3) | 59 | 86 | 23 / 14 |
| v1.4.82 / 153 | 5185/5317 (+3) | 56 | 86 | 24 / 13 |
| v1.4.83 / 154 | 5186/5317 (+1) | 54 | 86 | 24 / 13 |
| v1.4.84 / 155 | 5189/5317 (+3) | 52 | 85 | 23 / 13 |
| **v1.4.89 / 160** | **5347/5472 (golden set grew +155 slots)** | **50** | **83** | **22 / 11** |

**C↔S is parked.** Cascade headers score C0.90/S0.91 on the same pixels. Every
post-pass that “breaks” the tie has a favorite side and overfires. Occupancy
cannot break 10C/KC/QC (both ids free). Leave-suit-unassigned would not fix live
tableau arrows (color is enough) and would drop Evaluate.

**Active work: waste top identity** (playable card → wrong Waste→Tableau /
Waste→Foundation arrow). Do not start another C↔S scoring/header/occupancy round.

**v1.4.85 / versionCode 156:** waste OCR ROI top-inset fix for the
drop-shadow-band false positive above.
**v1.4.86-1.4.88 / versionCode 157-159 (from a concurrent session/Cursor):**
dedicated `ocrRankOverride` rules — OCR Five beats a tight-crop Four (fixes
`080754`); OCR/tight Six no longer steals a real legacy Nine or Eight; legacy
Nine now beats a tight/OCR Six magnet (`9H×3`, `190337`).
**v1.4.89 / versionCode 160:** the `tableauRunConsistencyDiagnostics` sanity
check and the Two/Three-restraint solver tweaks documented above.
**Evaluate-verified as of v1.4.89: 5347/5472 (98%)** — see the table below.
`230705`/`080754` cleared; `132126`/`132140` still mismatch with genuine
`ocr=miss:empty` (see the shadow-band correction above). The solver tweaks
and the run-consistency diagnostic are still unverified — Evaluate doesn't
score solver quality, and the diagnostic only surfaces via live play, not
the Evaluate flow (see its note above). **v1.4.90-1.4.92 / versionCode
161-163 (from a concurrent session/Cursor, pulled, not yet Evaluate-verified):**
v1.4.90 stopped `correctSixOnWaste`'s silent Six-steal on a genuine OCR miss;
v1.4.91 reverted v1.4.90's ink/score Eight-from-Four guess after it cost net
−30 (kept the Six-steal gate); v1.4.92 enlarged both waste OCR ROI paths to
0.48×0.42 (was 0.45×0.32 / 0.44×0.30) so a full two-loop Smash "8" isn't
clipped — see the shadow-band correction above. This is the current best
candidate fix for `132126`/`132140`.

**Golden growth:** add 15–25 boards, not a bulk dump. Snapshot when the
**rightmost waste card** is a **5, 6, 8, or J** and the assistant got that rank
wrong. Label that exposed card, not the peeking neighbor. Skip near-duplicate
`6C vs 6S` / `JS vs JC` (parked C0.83). Pixel-check new files before they stay;
wrong Six-on-Jack labels already cost rounds.

**Older geometry regression (still don't retry):** v1.4.17/88 re-anchored
`firstFaceTop` to the bottom card; 95%→93% (1897/2041). Reverted v1.4.18/89.

Remaining buckets, descending live-play value:
- Waste no-OCR Four→Six magnet — `230705` JD vs 6D, `132126`/`132140` 8D vs 6D.
  Tight Four, `correctSixOnWaste` at 0.38, every OCR region miss. v1.4.85's
  shadow-band ROI inset targets exactly this; unverified. Needs more playable
  5/6/8/J samples before another template if it doesn't move.
- ~~Waste OCR Five over Four not adopted (`080754`)~~ — fixed by v1.4.86's
  dedicated `ocrRankOverride` rule. Unverified on-device.
- Waste black-suit crop bias — **C0.83/S0.77** `wideMarginDirect→Clubs`
  (`6S vs 6C`, `210739 JS vs JC`, `3S vs 3C`). Not a 0.01 tie. Parked.
- FaceDown→FaceUp occupancy (18) and long-cascade compounding — buried slots,
  high-risk, not the arrow the user plays.
- H↔D (9+8) and parked C↔S (23+13).

## Don't

- Don't retune `BoardGeometryProfile` constants (`faceDownOverlap`, `faceUpOverlap`,
  `cardAspect`) *or* the arithmetic that derives cascade slot positions from them
  (`firstFaceTop`, `downStep`, `faceUpStep`) without very strong, broad (not
  single-sample) evidence — see "Validation discipline" above. Two different attempts
  in this area have now net-regressed on device: a direct `faceDownOverlap` retune
  (1011→1005/1068, an earlier session) and a `firstFaceTop` re-anchor that touched only
  the exposed-run positioning, not the constant itself (95%→93%, this session, v1.4.17).
  Both looked well-justified from a single pixel-verified example beforehand. If
  attempting this again, validate across many golden samples *before* writing Kotlin,
  not just the one sample that motivated the idea.
- Don't assume a two-pass tiebreak's *second* pass is where a wrong answer comes from
  just because it's the one that logs a decisive-looking branch name. Check which pass's
  diagnostic actually set the stored value first (see the black-suit-ambiguous note
  above) — patching the second pass can be a real, harmless improvement and still be a
  complete no-op for the case you're chasing.
- Don't treat a golden-set mismatch as a confirmed app bug without cropping and
  visually checking the real pixels first.
- Don't spend another round on C↔S template scores, header re-votes, Clubs/Spades
  bias, or occupancy-on-near-tie. v1.4.69, v1.4.78, and v1.4.79 all net-lost.
  v1.4.80 is the floor; leave it.
- Don't suggest stopping/wrapping up preemptively — this project runs as a long,
  iterative fix-verify loop and the user has consistently wanted to keep going past
  the point earlier sessions guessed was a reasonable stopping point.
