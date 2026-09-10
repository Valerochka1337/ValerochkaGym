package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncDaoTest : RoomDaoTest() {
  @Test
  fun `isolated health outbox retains literal bytes and cursor state`() = runTest {
    val bytes = " { \"operationId\" : \"op\" } ".encodeToByteArray()
    db.healthSyncDao().upsertOutbox(HealthSyncOutboxEntity("op", "GUEST", bytes, "hash"))
    db.healthSyncDao()
        .upsertState(HealthSyncStateEntity("GUEST", "cursor", needsFullRefresh = false))
    val outbox = requireNotNull(db.healthSyncDao().outbox("GUEST"))
    assertArrayEquals(bytes, outbox.requestBytes)
    db.healthSyncDao().markDispatched("op")
    assertEquals(true, db.healthSyncDao().outbox("GUEST")?.dispatched)
    assertEquals("cursor", db.healthSyncDao().state("GUEST")?.cursor)
  }
}
