package com.personal.solitaireassistant.game

enum class Suit(val isRed: Boolean) {
    Hearts(true),
    Diamonds(true),
    Clubs(false),
    Spades(false);

    fun oppositeColor(other: Suit): Boolean = isRed != other.isRed
}

enum class Rank(val value: Int) {
    Ace(1),
    Two(2),
    Three(3),
    Four(4),
    Five(5),
    Six(6),
    Seven(7),
    Eight(8),
    Nine(9),
    Ten(10),
    Jack(11),
    Queen(12),
    King(13);

    fun isOneBelow(other: Rank): Boolean = value + 1 == other.value

    fun isOneAbove(other: Rank): Boolean = value == other.value + 1

    companion object {
        fun fromValue(value: Int): Rank =
            entries.first { it.value == value }
    }
}

data class Card(
    val rank: Rank,
    val suit: Suit,
    val faceUp: Boolean = true,
    /** False when occupancy/color is known but rank/suit matching failed. */
    val known: Boolean = true
) {
    val id: String get() = if (known) "${rank.name}_${suit.name}" else "U_${if (suit.isRed) "R" else "B"}"

    fun canStackOnTableau(target: Card?): Boolean {
        if (!known) return false
        if (target == null) return rank == Rank.King
        if (!target.known) return false
        return faceUp &&
            target.faceUp &&
            suit.oppositeColor(target.suit) &&
            rank.isOneBelow(target.rank)
    }

    fun canPlaceOnFoundation(top: Card?): Boolean {
        if (!faceUp || !known) return false
        if (top == null) return rank == Rank.Ace
        if (!top.known) return false
        return suit == top.suit && rank.isOneAbove(top.rank)
    }

    override fun toString(): String {
        val face = if (faceUp) "" else "(down)"
        return "${rank.name.first()}${suit.name.first()}$face"
    }
}
