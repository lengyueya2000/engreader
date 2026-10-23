package com.engreader.app.tts

/**
 * Bookkeeping for the read-aloud's look-ahead: what is worth generating, what is
 * already covered, and what is already in flight.
 *
 * Kept out of [NeuralTts] and free of Android imports because its rules fail
 * *silently*, and each one is audible rather than visible when it breaks:
 *
 * - an in-flight mark that is never released blocks that sentence's look-ahead for the
 *   rest of the process, so every later visit to it pays the full synthesis again;
 * - a cap counted over both roles instead of per role lets the Chinese clip squeeze out
 *   the second English one, which is the clip the queue is about to ask for;
 * - trimming the *oldest* banked clip throws away the sentence that is about to be
 *   heard and re-synthesises it, which is the opposite of what a look-ahead is for;
 * - and the one that cost the most: counting banked clips against the same budget as
 *   queued work starves the engine. A cloning voice needs minutes of lead time for a
 *   long sentence — measured on a 4-core emulator, one 403-character sentence costs
 *   20 s to build against 28 s to hear, and a short sentence in front of it covers
 *   none of that. A budget that treats "two clips are banked" as "no more work may be
 *   admitted" leaves the worker idle exactly while the reader is waiting.
 *
 * Hence two separate caps: how much finished audio may be held (memory, and the only
 * thing the reader can play right now), and how deep the engine may have work queued
 * (throughput). The queue cap is the one that has to be deeper than the reader.
 *
 * The state is rebuilt functionally rather than mutated: the caps are per role and the
 * trim keeps the clips nearest the play head while discarding surplus speculation,
 * which reads better as "filter, then take the head" than as index surgery.
 *
 * Not thread-safe on purpose: [NeuralTts] holds one lock across every call, so a second
 * lock here would be dead weight and would make the two-step `request`/`complete`
 * sequence look atomic when it is not.
 */
internal class PrefetchLedger<T>(
    /** Finished clips a single role may hold. Bounds the samples kept in RAM. */
    private val holdPerRole: Int,
    /** Held *plus* in-flight, per role: how far the engine may run ahead. */
    private val queuePerRole: Int,
) {

    /**
     * One thing the reader might ask to hear.
     *
     * Rate, speaker and engine generation are deliberately *not* in the key: they belong
     * to the payload, and a caller that changed the rate has to be able to tell "I am
     * already building this" from "what is already built is not what I would play".
     */
    data class Key(val role: SpeechRole, val text: String)

    private var entries: List<Pair<Key, T>> = emptyList()
    private var inFlight: Set<Key> = emptySet()

    /**
     * Why a look-ahead was turned away. `null` from [request] means it was admitted.
     *
     * Named rather than folded into a `false` because the three are different faults:
     * [HELD] and [INFLIGHT] mean the work is already covered, while [BUDGET] means the
     * engine is being kept idle on purpose — and a refusal is invisible from outside, so
     * a run that stalls has no way to say which of the three it was.
     */
    internal enum class Refusal { HELD, INFLIGHT, BUDGET }

    /**
     * Asks for [key] to be generated, and answers whether it still needs generating.
     *
     * A refusal reserved nothing, so the caller must not queue work and must not call
     * [abandon] either.
     *
     * Held clips are refused *and* counted: a clip is only useful if it is playable for
     * this request, which is why [usable] is consulted rather than the key alone.
     */
    fun request(key: Key, usable: (T) -> Boolean): Refusal? {
        if (entries.any { it.first == key && usable(it.second) }) return Refusal.HELD
        if (key in inFlight) return Refusal.INFLIGHT
        if (entries.count { it.first.role == key.role } +
            inFlight.count { it.role == key.role } >= queuePerRole
        ) return Refusal.BUDGET
        inFlight = inFlight + key
        return null
    }

    /**
     * Banks a finished clip, keeping the [holdPerRole] clips of its role oldest first.
     *
     * The trim drops the *newest* of the role's clips. Over-cap banking happens when the
     * reader skipped forward while speculative work was in flight: the clips already
     * banked are the ones the queue is about to ask for, and the late arrival is audio
     * for a sentence that may never be heard. The next `request` re-admits whatever the
     * trim discarded, so nothing is lost permanently — only paid for twice, which is the
     * lesser harm than discarding the clip the reader stops for.
     */
    fun complete(key: Key, payload: T) {
        inFlight = inFlight - key
        entries = entries.filterNot { it.first == key } + (key to payload)
        val sameRole = entries.filter { it.first.role == key.role }
        if (sameRole.size > holdPerRole) {
            val surplus = sameRole.drop(holdPerRole).map { it.first }.toSet()
            entries = entries.filterNot { it.first in surplus }
        }
    }

    /** Releases a reservation whose generation will not happen, or did not succeed. */
    fun abandon(key: Key) {
        inFlight = inFlight - key
    }

    /** Removes and returns the held payload matching [predicate], oldest first. */
    fun take(predicate: (T) -> Boolean): T? {
        val index = entries.indexOfFirst { predicate(it.second) }
        if (index < 0) return null
        val entry = entries[index]
        entries = entries.filterIndexed { i, _ -> i != index }
        return entry.second
    }

    /** Every held clip, oldest first. */
    fun heldPayloads(): List<T> = entries.map { it.second }

    /**
     * True while [key] is admitted and has not been delivered.
     *
     * A look-ahead asks this of its own key when its task starts. A false answer means
     * somebody released the reservation while the task sat in the queue — in practice
     * [NeuralTts.speak], which builds a text itself when the reader reaches a sentence
     * whose look-ahead is still queued behind them, and releases that look-ahead's
     * reservation so the same text cannot be synthesised twice.
     */
    fun isInflight(key: Key): Boolean = key in inFlight

    /** Admitted but not yet delivered. Exposed so "nothing leaks" can be asserted. */
    fun inflightKeys(): Set<Key> = inFlight

    /** Discards every held clip, keeping reservations: see [NeuralTts.stop]. */
    fun clearHeld() {
        entries = emptyList()
    }

    /** Discards held clips *and* reservations, after a voice change makes them stale. */
    fun clearAll() {
        entries = emptyList()
        inFlight = emptySet()
    }
}
