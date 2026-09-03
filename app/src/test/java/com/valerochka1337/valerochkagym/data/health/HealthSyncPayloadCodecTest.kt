package com.valerochka1337.valerochkagym.data.health
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.domain.health.HealthSheetRows
import org.junit.Assert.*
import org.junit.Test
class HealthSyncPayloadCodecTest {
 @Test fun `report payload is deterministic and preserves typed rows`() { val r=HealthReportEntity("r",2,3,true,"REVOKED","DOCUMENT",4,"CBC","n",1); val a=HealthObservationEntity("a","r",1,2,false,4,"Hb","NUMBER","120","g", "ref","m","blood","lab","hb",2); val b=a.copy(syncId="b",valueType="TEXT",rawValue="ok",sourcePage=3); val one=HealthSyncPayloadCodec.report(r,listOf(b,a)); val two=HealthSyncPayloadCodec.report(r,listOf(a,b)); assertEquals(one,two); val decoded=HealthSyncPayloadCodec.decodeReport(one)!!; assertEquals(r,decoded.report); assertEquals(listOf(a,b),decoded.observations) }
 @Test fun `restriction sync payload and Sheets row exclude local original wording`() { val r=HealthRestrictionEntity("x",1,2,false,"ACTIVE","USER",3,null,4,"No sprint",originalText="Полная исходная формулировка"); val payload=HealthSyncPayloadCodec.restriction(r); assertFalse(payload.contains("Полная исходная формулировка")); assertNull(HealthSyncPayloadCodec.decodeRestriction(payload)!!.originalText); assertFalse(HealthSheetRows.restrictionRow(r,"hash","x:1").contains("Полная исходная формулировка")); assertEquals(HealthSheetRows.RESTRICTION_HEADER.size,HealthSheetRows.restrictionRow(r,"hash","x:1").size) }
 @Test fun `restriction and malformed payload decode safely`() { val r=HealthRestrictionEntity("x",1,2,false,"ACTIVE","USER",3,null,4,"No sprint"); assertEquals(r,HealthSyncPayloadCodec.decodeRestriction(HealthSyncPayloadCodec.restriction(r))); assertNull(HealthSyncPayloadCodec.decodeReport("bad")) }
}
