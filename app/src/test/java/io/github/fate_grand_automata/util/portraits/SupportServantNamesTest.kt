package io.github.fate_grand_automata.util.portraits

import kotlin.test.*

class SupportServantNamesTest {
    @Test fun `base identity and every ascension or costume name stay on the same internal ID`() {
        val base = SupportServantNames.parse("""[{"id":304800,"name":"멜뤼진","battleName":"멜뤼진"}]""",ServantNameTable.Base)
        val limits = SupportServantNames.parse("""[
          {"svtId":304800,"limitCount":0,"script":{"overWriteServantName":"요정기사 랜슬롯","overWriteServantBattleName":"요정기사 랜슬롯","overwriteSvtDetailName":"요정기사 랜슬롯"}},
          {"svtId":304800,"limitCount":11,"script":{"overWriteServantName":"요정기사 랜슬롯"}},
          {"svtId":304800,"limitCount":3,"script":{}},
          {"svtId":1,"limitCount":2,"script":{"overWriteServantShortName":"다른 이름"}}
        ]""",ServantNameTable.Ascension)
        val merged = SupportServantNames.merge(listOf(base,limits))
        assertEquals(setOf("멜뤼진","요정기사 랜슬롯"),merged[304800])
        assertEquals(setOf("다른 이름"),merged[1])
        assertEquals(merged,SupportServantNames.parseCache(SupportServantNames.encodeCache(merged)))
    }
    @Test fun `hidden identities include true and story names but never NP skill or voice names`() {
        val hidden = SupportServantNames.parse("""[
          {"svtId":402500,"name":"크리스토퍼 콜럼버스","battleName":"콜럼버스"},
          {"svtId":402500,"name":"레지스탕스의 라이더","battleName":"레지스탕스의 라이더"}
        ]""",ServantNameTable.Hidden)
        val limits = SupportServantNames.parse("""[{"svtId":402500,"script":{"overWriteTDName":"보구 이름","skillName":"스킬 이름","overWriteServantName":""}}]""",ServantNameTable.Ascension)
        assertEquals(setOf("크리스토퍼 콜럼버스","콜럼버스","레지스탕스의 라이더"),SupportServantNames.merge(listOf(hidden,limits))[402500])
    }
    @Test fun `invalid tables and cache are rejected instead of attaching names to wrong IDs`() {
        for (text in listOf("{}","[{}]","[{\"id\":1,\"name\":42}]")) {
            assertFailsWith<PortraitImportException> { SupportServantNames.parse(text,ServantNameTable.Base) }
        }
        for (text in listOf("[]","{\"bad\":[\"name\"]}","{\"1\":[42]}","{\"1\":[\"\"]}")) {
            assertFailsWith<PortraitImportException> { SupportServantNames.parseCache(text) }
        }
    }
}
