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
