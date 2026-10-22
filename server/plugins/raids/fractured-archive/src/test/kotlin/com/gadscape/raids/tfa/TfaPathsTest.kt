package com.gadscape.raids.tfa

import kotlin.test.Test
import kotlin.test.assertEquals

class TfaPathsTest {
    @Test
    fun defaultDataDirIsDataRaidsTfa() {
        System.clearProperty(TfaPaths.DATA_DIR_PROPERTY)
        assertEquals("data/raids/tfa", TfaPaths.dataDir.toString().replace('\\', '/'))
        assertEquals("data/raids/tfa/remap.tsv", TfaPaths.remapFile.toString().replace('\\', '/'))
    }
}
