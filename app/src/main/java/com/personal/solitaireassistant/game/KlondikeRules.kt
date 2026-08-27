package com.personal.solitaireassistant.game

object KlondikeRules {
    const val DRAW_COUNT = 3

    fun isLegal(state: GameState, move: Move): Boolean =
        apply(state, move) != null

    fun apply(state: GameState, move: Move): GameState? = when (move) {
        is Move.TableauToTableau -> applyTableauToTableau(state, move)
        is Move.TableauToFoundation -> applyTableauToFoundation(state, move)
        is Move.WasteToTableau -> applyWasteToTableau(state, move)
        is Move.WasteToFoundation -> applyWasteToFoundation(state, move)
        is Move.FoundationToTableau -> applyFoundationToTableau(state, move)
        Move.DrawStock -> applyDraw(state)
        Move.RecycleWaste -> applyRecycle(state)
    }

    fun legalMoves(state: GameState): List<Move> {
        val moves = mutableListOf<Move>()

        for (from in 0 until 7) {
            val column = state.tableau[from]
            if (column.isEmpty()) continue

            val firstFaceUp = column.indexOfFirst { it.faceUp }
            if (firstFaceUp < 0) continue

            for (start in firstFaceUp until column.size) {
                if (!isValidRun(column.subList(start, column.size))) continue
                val moving = column[start]
                for (to in 0 until 7) {
                    if (to == from) continue
                    val target = state.tableauTop(to)
                    if (moving.canStackOnTableau(target)) {
                        moves += Move.TableauToTableau(from, start, to)
                    }
                }
            }

            val top = column.last()
            if (top.faceUp) {
                foundationIndexFor(state, top)?.let { foundation ->
                    moves += Move.TableauToFoundation(from, foundation)
                }
            }
        }

        state.wasteTop()?.let { wasteTop ->
            for (to in 0 until 7) {
                if (wasteTop.canStackOnTableau(state.tableauTop(to))) {
                    moves += Move.WasteToTableau(to)
                }
            }
            foundationIndexFor(state, wasteTop)?.let { foundation ->
                moves += Move.WasteToFoundation(foundation)
            }
        }

        for (foundation in state.foundations.indices) {
            val top = state.foundations[foundation].lastOrNull() ?: continue
            for (to in 0 until 7) {
                if (top.canStackOnTableau(state.tableauTop(to))) {
                    moves += Move.FoundationToTableau(foundation, to)
                }
            }
        }

        if (state.stock.isNotEmpty()) {
            moves += Move.DrawStock
        } else if (state.waste.isNotEmpty()) {
            moves += Move.RecycleWaste
        }

        return moves
    }

    fun isSafeFoundationMove(state: GameState, card: Card): Boolean {
        // Conservative Baker-style heuristic: Aces are always safe; Two and
        // higher require both same-color next-lower cards already founded, or
        // both opposite-color cards one rank below founded.
        if (card.rank == Rank.Ace) return true
        val needed = card.rank.value - 1
        val opposite = Suit.entries.filter { it.isRed != card.suit.isRed }
        val sameColor = Suit.entries.filter { it != card.suit && it.isRed == card.suit.isRed }

        fun foundedAtLeast(suit: Suit, rankValue: Int): Boolean {
            val pile = state.foundations.firstOrNull { it.lastOrNull()?.suit == suit }
                ?: return false
            return (pile.lastOrNull()?.rank?.value ?: 0) >= rankValue
        }

        val oppositeReady = opposite.all { foundedAtLeast(it, needed) }
        val sameColorReady = sameColor.all { foundedAtLeast(it, needed - 1) }
        return oppositeReady || sameColorReady
    }

    private fun applyTableauToTableau(
        state: GameState,
        move: Move.TableauToTableau
    ): GameState? {
        val from = state.tableau[move.fromColumn]
        if (move.startIndex !in from.indices) return null
        val moving = from.subList(move.startIndex, from.size)
        if (moving.isEmpty() || !moving.first().faceUp) return null
        if (!isValidRun(moving)) return null
        val target = state.tableauTop(move.toColumn)
        if (!moving.first().canStackOnTableau(target)) return null

        val newFrom = from.take(move.startIndex).toMutableList()
        if (newFrom.isNotEmpty() && !newFrom.last().faceUp) {
            newFrom[newFrom.lastIndex] = newFrom.last().copy(faceUp = true)
        }
        val newTo = state.tableau[move.toColumn] + moving
        val tableau = state.tableau.toMutableList()
        tableau[move.fromColumn] = newFrom
        tableau[move.toColumn] = newTo
        return state.copy(tableau = tableau)
    }

    private fun applyTableauToFoundation(
        state: GameState,
        move: Move.TableauToFoundation
    ): GameState? {
        val from = state.tableau[move.fromColumn]
        val card = from.lastOrNull() ?: return null
        if (!card.faceUp) return null
        val foundation = state.foundations[move.toFoundation]
        if (!card.canPlaceOnFoundation(foundation.lastOrNull())) return null

        val newFrom = from.dropLast(1).toMutableList()
        if (newFrom.isNotEmpty() && !newFrom.last().faceUp) {
            newFrom[newFrom.lastIndex] = newFrom.last().copy(faceUp = true)
        }
        val foundations = state.foundations.toMutableList()
        foundations[move.toFoundation] = foundation + card
        val tableau = state.tableau.toMutableList()
        tableau[move.fromColumn] = newFrom
        return state.copy(tableau = tableau, foundations = foundations)
    }

    private fun applyWasteToTableau(
        state: GameState,
        move: Move.WasteToTableau
    ): GameState? {
        val card = state.wasteTop() ?: return null
        if (!card.canStackOnTableau(state.tableauTop(move.toColumn))) return null
        val tableau = state.tableau.toMutableList()
        tableau[move.toColumn] = tableau[move.toColumn] + card
        return state.copy(
            waste = state.waste.dropLast(1),
            tableau = tableau
        )
    }

    private fun applyWasteToFoundation(
        state: GameState,
        move: Move.WasteToFoundation
    ): GameState? {
        val card = state.wasteTop() ?: return null
        val foundation = state.foundations[move.toFoundation]
        if (!card.canPlaceOnFoundation(foundation.lastOrNull())) return null
        val foundations = state.foundations.toMutableList()
        foundations[move.toFoundation] = foundation + card
        return state.copy(
            waste = state.waste.dropLast(1),
            foundations = foundations
        )
    }

    private fun applyFoundationToTableau(
        state: GameState,
        move: Move.FoundationToTableau
    ): GameState? {
        val foundation = state.foundations[move.fromFoundation]
        val card = foundation.lastOrNull() ?: return null
        if (!card.canStackOnTableau(state.tableauTop(move.toColumn))) return null
        val foundations = state.foundations.toMutableList()
        foundations[move.fromFoundation] = foundation.dropLast(1)
        val tableau = state.tableau.toMutableList()
        tableau[move.toColumn] = tableau[move.toColumn] + card
        return state.copy(tableau = tableau, foundations = foundations)
    }

    private fun applyDraw(state: GameState): GameState? {
        if (state.stock.isEmpty()) return null
        val draw = state.stock.take(DRAW_COUNT)
        return state.copy(
            stock = state.stock.drop(draw.size),
            waste = state.waste + draw
        )
    }

    private fun applyRecycle(state: GameState): GameState? {
        if (state.stock.isNotEmpty() || state.waste.isEmpty()) return null
        // Recycle preserves draw-3 order by reversing waste into stock.
        return state.copy(
            stock = state.waste.asReversed(),
            waste = emptyList(),
            recyclesUsed = state.recyclesUsed + 1
        )
    }

    private fun foundationIndexFor(state: GameState, card: Card): Int? {
        for (i in state.foundations.indices) {
            if (card.canPlaceOnFoundation(state.foundations[i].lastOrNull())) {
                // Prefer matching suit pile if already started; otherwise first empty.
                val top = state.foundations[i].lastOrNull()
                if (top == null || top.suit == card.suit) return i
            }
        }
        return null
    }

    private fun isValidRun(cards: List<Card>): Boolean {
        if (cards.isEmpty()) return false
        if (cards.any { !it.faceUp || it.inferred }) return false
        for (i in 0 until cards.lastIndex) {
            val upper = cards[i]
            val lower = cards[i + 1]
            if (!lower.canStackOnTableau(upper)) return false
        }
        return true
    }
}
