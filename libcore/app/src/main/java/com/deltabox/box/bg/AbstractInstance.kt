package com.deltabox.box.bg

import java.io.Closeable

interface AbstractInstance : Closeable {

    fun launch()

}