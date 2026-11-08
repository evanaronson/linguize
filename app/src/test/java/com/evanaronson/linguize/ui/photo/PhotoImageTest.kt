package com.evanaronson.linguize.ui.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhotoImageTest {
    @Test fun smallImagesKeepTheirSize() {
        assertEquals(800 to 600, PhotoImage.scaledSize(800, 600))
        assertEquals(1600 to 1600, PhotoImage.scaledSize(1600, 1600))
    }

    @Test fun theLongSideShrinksToTheLimitKeepingTheAspectRatio() {
        // A 12 MP phone photo, landscape and portrait.
        assertEquals(1600 to 1200, PhotoImage.scaledSize(4000, 3000))
        assertEquals(1200 to 1600, PhotoImage.scaledSize(3000, 4000))
        // A tall phone screenshot.
        assertEquals(738 to 1600, PhotoImage.scaledSize(1080, 2340))
        assertEquals(500 to 250, PhotoImage.scaledSize(4000, 2000, maxSide = 500))
    }

    @Test fun aVeryThinImageKeepsAtLeastOnePixel() {
        assertEquals(1600 to 1, PhotoImage.scaledSize(100_000, 10))
    }

    @Test fun anEmptyImageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhotoImage.scaledSize(0, 100) }
    }

    @Test fun compressionStepsDownUntilTheImageFits() {
        val tried = mutableListOf<Int>()
        val bytes = PhotoImage.fitting(maxBytes = 1000) { quality ->
            tried += quality
            ByteArray(if (quality > 65) 2000 else 900)
        }
        assertEquals(listOf(85, 75, 65), tried)
        assertEquals(900, bytes.size)
    }

    @Test fun theFirstQualityIsKeptWhenItFits() {
        val tried = mutableListOf<Int>()
        PhotoImage.fitting(maxBytes = 1000) { tried += it; ByteArray(10) }
        assertEquals(listOf(85), tried)
    }

    @Test fun anImageThatNeverFitsIsSentAtTheLowestQuality() {
        val bytes = PhotoImage.fitting(maxBytes = 10) { ByteArray(100 + it) }
        assertEquals(100 + PhotoImage.QUALITIES.last(), bytes.size)
    }
}
