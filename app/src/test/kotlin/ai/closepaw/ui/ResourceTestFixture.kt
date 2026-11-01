package ai.closepaw.ui

import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk

/** Surface the chosen label and its arguments without needing a device's resource table. */
fun resourceLabels(): Resources = mockk {
    every { getString(any()) } answers { firstArg<Int>().toString() }
    every { getString(any(), *anyVararg()) } answers {
        "${firstArg<Int>()}:${secondArg<Array<*>>().joinToString()}"
    }
}
