package com.aios.messaging.mms.platform

import android.content.Context
import com.aios.messaging.mms.MmsTransport

object MmsTransportFactory {
    fun create(context: Context, listener: MmsTransport.Listener): MmsTransport =
        PlatformMmsTransport(context.applicationContext, listener)
}
