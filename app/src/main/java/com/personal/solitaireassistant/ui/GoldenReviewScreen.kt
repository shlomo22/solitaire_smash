package com.personal.solitaireassistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.vision.ErrorCaptureReviewHints
import com.personal.solitaireassistant.vision.RecognizedSlot
import com.personal.solitaireassistant.vision.SlotGuess
import com.personal.solitaireassistant.vision.SlotKind
import com.personal.solitaireassistant.vision.SuspiciousSlotHint

@Composable
fun GoldenReviewScreen(
    bitmap: Bitmap,
    slots: List<RecognizedSlot>,
    onSave: (List<SlotGuess>) -> Unit,
    onDiscard: () -> Unit,
    onRecapture: () -> Unit,
    initialTruths: List<SlotGuess>? = null,
    allowRecapture: Boolean = true,
    suspiciousHints: List<SuspiciousSlotHint> = emptyList(),
    title: String = "Confirm recognized cards",
    subtitle: String = "Tap a row below to correct rank and suit. Inferred cascade cards are optional."
) {
    val hasSuspicious = suspiciousHints.isNotEmpty()
    val helpText = buildString {
        append(subtitle)
        append("\nBadge colors: green = confident read, yellow~ = suit unclear, dim = inferred.")
        if (hasSuspicious) {
            append(" Orange rows = cards flagged by the error capture.")
        }
    }
    val truths = remember(slots, initialTruths) {
        mutableStateListOf<SlotGuess>().apply {
            addAll(initialTruths ?: slots.map { it.engine })
        }
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            helpText,
            style = MaterialTheme.typography.bodySmall
        )

        BoardSnapshotMap(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(slots) { index, slot ->
                val truth = truths[index]
                if (truth.kind == SlotKind.FaceDown) return@itemsIndexed
                SlotRow(
                    slot = slot,
                    truth = truth,
                    suspiciousHint = ErrorCaptureReviewHints.hintFor(slot, suspiciousHints),
                    onClick = { editingIndex = index }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (allowRecapture) {
                OutlinedButton(
                    onClick = onRecapture,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Recapture")
                }
            }
            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.weight(1f)
            ) {
                Text("Discard")
            }
            Button(
                onClick = { onSave(truths.toList()) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
        }
    }

    val editIndex = editingIndex
    if (editIndex != null) {
        SlotEditorDialog(
            slot = slots[editIndex],
            current = truths[editIndex],
            onDismiss = { editingIndex = null },
            onConfirm = { guess ->
                truths[editIndex] = guess
                editingIndex = null
            }
        )
    }
}

@Composable
private fun BoardSnapshotMap(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Frozen board",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun SlotRow(
    slot: RecognizedSlot,
    truth: SlotGuess,
    suspiciousHint: SuspiciousSlotHint?,
    onClick: () -> Unit
) {
    val changed = truth != slot.engine
    val rowColor = if (suspiciousHint != null) {
        Color(0x33FF7043)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowColor)
            .then(
                if (suspiciousHint != null) {
                    Modifier.border(1.dp, Color(0xFFFF7043), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pileCaption(slot), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Engine: ${slot.engine.shortLabel()}",
                style = MaterialTheme.typography.bodySmall
            )
            suspiciousHint?.let {
                Text(
                    "Problem: ${it.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE64A19)
                )
            }
        }
        Text(
            text = truth.shortLabel(),
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor(truth, slot.inferred))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        if (changed) {
            Spacer(Modifier.width(6.dp))
            Text("edited", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotEditorDialog(
    slot: RecognizedSlot,
    current: SlotGuess,
    onDismiss: () -> Unit,
    onConfirm: (SlotGuess) -> Unit
) {
    var kind by remember { mutableStateOf(current.kind) }
    var rank by remember { mutableStateOf(current.rank ?: Rank.Ace) }
    var suit by remember { mutableStateOf(current.suit ?: Suit.Hearts) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(pileCaption(slot), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Engine guess: ${slot.engine.shortLabel()}",
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Occupancy", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlotKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.name) }
                        )
                    }
                }

                if (kind == SlotKind.FaceUp || kind == SlotKind.Unknown) {
                    Text("Rank", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Rank.entries.forEach { option ->
                            FilterChip(
                                selected = rank == option,
                                onClick = {
                                    rank = option
                                    if (kind == SlotKind.Unknown) kind = SlotKind.FaceUp
                                },
                                label = { Text(rankChip(option)) }
                            )
                        }
                    }
                    Text("Suit", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Suit.entries.forEach { option ->
                            FilterChip(
                                selected = suit == option,
                                onClick = {
                                    suit = option
                                    if (kind == SlotKind.Unknown) kind = SlotKind.FaceUp
                                },
                                label = {
                                    Text(
                                        option.name,
                                        color = if (option.isRed) Color(0xFFC62828) else Color.Unspecified
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                when (kind) {
                                    SlotKind.Empty -> SlotGuess(SlotKind.Empty)
                                    SlotKind.FaceDown -> SlotGuess(SlotKind.FaceDown)
                                    SlotKind.Unknown -> SlotGuess(SlotKind.Unknown)
                                    SlotKind.FaceUp -> SlotGuess(
                                        kind = SlotKind.FaceUp,
                                        rank = rank,
                                        suit = suit
                                    )
                                }
                            )
                        }
                    ) { Text("Apply") }
                }
            }
        }
    }
}

private fun pileCaption(slot: RecognizedSlot): String {
    val base = when (val pile = slot.pile) {
        PileRef.Stock -> "Stock"
        PileRef.Waste -> "Waste"
        is PileRef.Foundation -> "Foundation ${pile.index + 1}"
        is PileRef.Tableau -> "Tableau ${pile.index + 1}"
    }
    val extra = when {
        slot.inferred -> " inferred"
        slot.index > 0 -> " #${slot.index + 1}"
        else -> ""
    }
    return base + extra
}

private fun rankChip(rank: Rank): String = when (rank) {
    Rank.Ace -> "A"
    Rank.Jack -> "J"
    Rank.Queen -> "Q"
    Rank.King -> "K"
    Rank.Ten -> "10"
    else -> rank.value.toString()
}

private fun badgeColor(guess: SlotGuess, inferred: Boolean): Color {
    val base = when (guess.kind) {
        SlotKind.Empty -> Color(0xCC546E7A)
        SlotKind.FaceDown -> Color(0xCC1565C0)
        SlotKind.Unknown -> Color(0xCCC62828)
        SlotKind.FaceUp -> if (guess.suitAmbiguous) {
            Color(0xCCF9A825)
        } else {
            Color(0xCC2E7D32)
        }
    }
    return if (inferred) base.copy(alpha = 0.55f) else base
}
