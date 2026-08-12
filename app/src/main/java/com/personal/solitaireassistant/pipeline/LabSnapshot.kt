package com.personal.solitaireassistant.pipeline

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.vision.DetectionResult

data class LabCardRegion(
    val id: String,
    val label: String,
    val region: BoardRegion,
    val detectedCard: Card?
)

data class LabSnapshot(
    val bitmap: Bitmap,
    val detection: DetectionResult,
    val regions: List<LabCardRegion>
)
