package com.linkn.screenintake.store

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageLayoutTest {
    @Test fun `finance files stay together`() {
        listOf("账本.csv", "卡片.csv", "持仓.csv", "交易记录.csv", "转账记录.csv").forEach {
            assertEquals(StorageLayout.FINANCE, StorageLayout.domainForFile(it))
        }
    }

    @Test fun `health files stay together`() {
        listOf("健康.csv", "运动.csv", "身体数据.csv", "数字健康.csv", "体重.csv", "饮食记录.md").forEach {
            assertEquals(StorageLayout.HEALTH, StorageLayout.domainForFile(it))
        }
    }

    @Test fun `work and system files are separated`() {
        assertEquals(StorageLayout.WORK, StorageLayout.domainForFile("待办.md"))
        assertEquals(StorageLayout.WORK, StorageLayout.domainForFile("灵感.md"))
        assertEquals(StorageLayout.SYSTEM, StorageLayout.domainForFile("未知文件.json"))
    }
}
