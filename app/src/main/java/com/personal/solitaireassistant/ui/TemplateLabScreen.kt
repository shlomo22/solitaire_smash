package com.personal.solitaireassistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.settings.UserTemplateStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateLabScreen(
    onBack: () -> Unit,
    viewModel: TemplateLabViewModel = viewModel(
        factory = TemplateLabViewModelFactory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val serviceStatus by viewModel.serviceStatus.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        CaptureService.setTemplateLabMode(true)
        onDispose {
            CaptureService.setTemplateLabMode(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Template Lab", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Text(
            "Capture rank-corner and suit-badge samples from live gameplay. " +
                "Hints are paused while this screen is open.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Capture: ${serviceStatus::class.simpleName}", style = MaterialTheme.typography.bodySmall)
        Text(uiState.message, color = MaterialTheme.colorScheme.primary)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Card regions", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.regions.forEach { region ->
                        FilterChip(
                            selected = region.id == uiState.selectedRegionId,
                            onClick = { viewModel.selectRegion(region.id) },
                            label = {
                                Text(
                                    region.label +
                                        (region.detectedCard?.let { " (${it.id})" } ?: "")
                                )
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CropPreview(
                title = "Rank corner",
                bitmap = uiState.rankCornerPreview,
                modifier = Modifier.weight(1f)
            )
            CropPreview(
                title = "Suit badge",
                bitmap = uiState.suitBadgePreview,
                modifier = Modifier.weight(1f)
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Confirm label", style = MaterialTheme.typography.titleMedium)
                Text("Rank", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Rank.entries.forEach { rank ->
                        FilterChip(
                            selected = uiState.selectedRank == rank,
                            onClick = { viewModel.setRank(rank) },
                            label = { Text(rank.label) }
                        )
                    }
                }
                Text("Suit", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Suit.entries.forEach { suit ->
                        FilterChip(
                            selected = uiState.selectedSuit == suit,
                            onClick = { viewModel.setSuit(suit) },
                            label = { Text(suit.name.take(1)) }
                        )
                    }
                }
            }
        }

        uiState.previewScores?.let { scores ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Top rank scores", style = MaterialTheme.typography.titleSmall)
                    scores.topRanks.forEach { (rank, score) ->
                        Text("${rank.label}: ${"%.2f".format(score)}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Top suit scores", style = MaterialTheme.typography.titleSmall)
                    scores.topSuits.forEach { (suit, score) ->
                        Text("${suit.name}: ${"%.2f".format(score)}")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = viewModel::saveTemplate,
                modifier = Modifier.weight(1f)
            ) { Text("Save template") }
            OutlinedButton(
                onClick = viewModel::testRecognition,
                modifier = Modifier.weight(1f)
            ) { Text("Test") }
        }
        OutlinedButton(
            onClick = { viewModel.exportTemplates() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export ZIP") }

        uiState.coverage?.let { coverage ->
            CoverageGrid(coverage)
        }

        Text(
            "Stored at: ${uiState.templateRootPath}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Tip: save 2+ samples per rank (especially 2,3,5,6,8,9) and all four suits " +
                "from tableau, waste, and foundation.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CropPreview(
    title: String,
    bitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageGrid(coverage: UserTemplateStore.TemplateCoverage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Coverage (rank corner / suit badge)", style = MaterialTheme.typography.titleMedium)
            Rank.entries.forEach { rank ->
                val cornerCount = coverage.rankCorner[rank] ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(rank.label, modifier = Modifier.width(36.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Suit.entries.forEach { suit ->
                            val suitCount = coverage.suitBadge[suit] ?: 0
                            val highlight = cornerCount == 0
                            Text(
                                text = "${suit.name.take(1)}:$suitCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (highlight) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    Text(
                        "R:$cornerCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cornerCount == 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    }
}

private val Rank.label: String
    get() = when (this) {
        Rank.Ace -> "A"
        Rank.Jack -> "J"
        Rank.Queen -> "Q"
        Rank.King -> "K"
        else -> value.toString()
    }
