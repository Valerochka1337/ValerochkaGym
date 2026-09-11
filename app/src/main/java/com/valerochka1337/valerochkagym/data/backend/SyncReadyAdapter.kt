package com.valerochka1337.valerochkagym.data.backend

/** A server-acknowledged context that may safely be sent to an AI draft endpoint. */
sealed interface SyncReady {
  data class Ready(
      val owner: String,
      val revision: Long,
      val catalogRevision: Long,
      val sessionEpoch: Long = 0L,
      val cacheGeneration: Long = 0L,
  ) : SyncReady

  data object Blocked : SyncReady

  data class Failure(val message: String, val cause: Exception? = null) : SyncReady
}

interface SyncReadySource {
  suspend fun await(): SyncReady

  /** A fail-closed, side-effect-free freshness check for a previously issued receipt. */
  suspend fun isCurrent(ready: SyncReady.Ready): Boolean = false
}

/** Keeps AI callers from interpreting a best-effort [BackendSync.run] as an acknowledgement. */
class SyncReadyAdapter @javax.inject.Inject constructor(private val sync: BackendSync) :
    SyncReadySource {
  override suspend fun await(): SyncReady = sync.awaitAiReady()

  override suspend fun isCurrent(ready: SyncReady.Ready): Boolean = sync.isAiReadyCurrent(ready)
}
