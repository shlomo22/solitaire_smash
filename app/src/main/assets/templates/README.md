# Card templates

Place PNG templates for rank-corner and suit-badge matching.

## Bundled assets (fallback)

- `rank_corner_ace.png` … `rank_corner_king.png` (preferred)
- `rank_ace.png` … `rank_king.png` (legacy — still loaded as corner templates)
- `suit_badge_hearts.png`, … (preferred)
- `suit_hearts.png`, … (legacy)
- `empty_slot.png`, `face_down.png`

Add `_alt2.png`, `_alt3.png`, etc. for alternate crops.

## Crop specs (within a face-up card)

| Type | Region | Purpose |
|------|--------|---------|
| Rank corner | left 0–30%, top 0–24% | Small corner digit |
| Suit badge | left 52–95%, top 0–20% | Upper-right suit pip |
| Rank glyph (optional) | center 35–65% × 25–70% height | J/Q/K center glyph |

## In-app Template Lab (recommended)

1. Start capture over Solitaire Smash.
2. Open **Template Lab** from the assistant settings screen (hints pause).
3. Tap a detected card region (tableau, waste, foundation).
4. Confirm rank and suit labels; tap **Save template**.
5. Repeat for ranks 2,3,5,6,8,9 and all four suits from different pile types.
6. Use **Export ZIP** to copy templates from `files/templates/` via adb.

User templates override bundled assets and reload immediately without reinstall.

Pull user templates:

```bash
adb exec-out run-as com.personal.solitaireassistant tar -C files templates > smash_templates.tar
```
