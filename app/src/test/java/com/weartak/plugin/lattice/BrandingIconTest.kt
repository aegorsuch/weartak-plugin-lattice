// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.lattice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandingIconTest {
    @Test fun packageAndRoundLaunchersUseSameAdaptiveCompanyIconAtWatchSizes() {
        val context = RuntimeEnvironment.getApplication()
        val application = context.applicationInfo
        assertEquals(R.mipmap.ic_lattice, application.icon)
        val manifest = context.assets.openXmlResourceParser("AndroidManifest.xml")
        manifest.use {
            while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name == "application") {
                    assertEquals(application.icon, it.getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", "roundIcon", 0))
                }
            }
        }
        for (size in listOf(24, 32, 48)) {
            val icon = context.getDrawable(application.icon) as AdaptiveIconDrawable
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            var darkPixels = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) > 128 && Color.red(pixel) < 80) {
                        darkPixels++
                        assertTrue("Company mark must remain clear of mask edges", x > 1 && y > 1 && x < size - 2 && y < size - 2)
                    }
                }
            }
            assertTrue("Company mark must remain visible at ${size}px", darkPixels > size)
            bitmap.recycle()
        }
    }
}
