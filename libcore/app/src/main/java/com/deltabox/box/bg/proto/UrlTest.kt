package com.deltabox.box.bg.proto

import com.deltabox.box.database.DataStore
import com.deltabox.box.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 5000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

}