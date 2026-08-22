package dev.roesler.marquee.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAccessTest {
    private fun provider(access: String) =
        WatchProvider(id = 1, name = "Test", logoUrl = null, packageName = "com.test", access = access)

    @Test
    fun freeAndAdSupportedTiersCountAsFree() {
        assertTrue(provider(WatchAccess.FREE).isFreeWithAds)
        assertTrue(provider(WatchAccess.ADS).isFreeWithAds)
    }

    @Test
    fun paidTiersAreNotFree() {
        assertFalse(provider(WatchAccess.STREAM).isFreeWithAds)
        assertFalse(provider(WatchAccess.RENT).isFreeWithAds)
        assertFalse(provider(WatchAccess.BUY).isFreeWithAds)
    }
}
