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
  front card can fail the 6% gate.

## Current state (as of v1.4.81 / versionCode 152, pushed, not yet Evaluate-verified)

**v1.4.80 / 151** restored the v1.4.77 suit path and Evaluate-matched **5179/5317**.
**v1.4.81** gates `ocrRankOverride`'s `isConfusionPair` fallback when *both* waste
crops already produced a rank — so a later left-fan OCR (the covered 2nd card)
cannot turn agreed Queen into Ten or Jack+Four into Three. Single-crop
Jack/Three and Jack/Five OCR overrides are unchanged. Awaiting Evaluate.

Golden set: **151 samples / 5317 labeled slots**. On-device floor:

| version | accuracy | C→S | S→C | suit |
|---|---|---|---|---|
| v1.4.77 / 148 | **5179/5317 (97%)** | 24 | 14 | 87 |
| v1.4.78 / 149 Clubs-on-near-tie | 5171/5317 (−8) | 20 | **25** | 94 |
| v1.4.79 / 150 header recheck | 5092/5317 (−87) | **73** | **51** | 173 |
| **v1.4.80 / 151 revert** | **5179/5317 (97%)** | **24** | **14** | **87** |

v1.4.80 is a clean restore of the v1.4.77 suit path (no header-black-suit, no
Clubs-on-0.01-tie, `locateBadge` and waste occupancy as they were). Rank 62,
occupancy 24, missing 4 — unchanged across 77/78/80.

**C↔S is parked.** Cascade headers score C0.90/S0.91 on the same pixels. Every
post-pass that “breaks” the tie has a favorite side and overfires. Occupancy
cannot break 10C/KC/QC (both ids free). Leave-suit-unassigned would not fix live
tableau arrows (color is enough) and would drop Evaluate.

**Active work: waste top identity** (playable card → wrong Waste→Tableau /
Waste→Foundation arrow). Do not start another C↔S scoring/header/occupancy round.

**Older geometry regression (still don't retry):** v1.4.17/88 re-anchored
`firstFaceTop` to the bottom card; 95%→93% (1897/2041). Reverted v1.4.18/89.

Remaining buckets, descending live-play value:
- Waste fan 2nd-card / OCR-left override — `QH vs 10H`, `QC vs 10C`, `JS vs 3C`,
  `JC vs 5S`. Confirmed in pixels; `isConfusionPair` after two template reads is
  the first lever.
- Waste rank magnet (Jack/Eight → Four/Six) — `JC vs 6C`, `JH vs 6H`, `8H vs 4H`.
  Tight crop reads Four; `correctSixOnWaste` or fusion keeps it. Separate from OCR.
- Waste black-suit crop bias — many waste blacks score **C0.83/S0.77**
  `wideMarginDirect→Clubs` (`6S vs 6C`, `JS vs JC`, `3S vs 3C`). Not a 0.01 tie.
- FaceDown→FaceUp occupancy (18) and long-cascade compounding — buried slots,
  high-risk, not the arrow the user plays.
- H↔D (9+8) and parked C↔S (24+14).

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
