package com.valerochka1337.valerochkagym.domain

/**
 * Non-serializable permit created only by the exact local command parser. Equality includes all
 * operations and immutable anchors, so a model cannot add a suffix or reuse a stale anchor.
 */
class CommandAuthority
private constructor(
    private val packet: WorkoutChangeSet.Packet,
    private val anchors: Anchors,
) {
  data class Anchors(val currentSetId: String?, val previousSetId: String?, val nextSetId: String?)

  fun authorizes(candidate: WorkoutChangeSet.Packet, candidateAnchors: Anchors): Boolean =
      packet == candidate && anchors == candidateAnchors

  companion object {
    fun local(packet: WorkoutChangeSet.Packet, anchors: Anchors): CommandAuthority =
        CommandAuthority(packet, anchors)
  }
}
