package io.github.ottershelf.feature.bookedit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Photos made ready to upload: scaled down (never up), JPEG, on white, and a camera shot cut to its frame. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverImageTest {

    @Test
    fun largePhotosFitTheLongSideAndKeepTheirShape() {
        assertEquals(1200 to 1600, CoverScale.target(3000, 4000, 1600))
        assertEquals(1600 to 1200, CoverScale.target(4000, 3000, 1600))
        assertEquals(1067 to 1600, CoverScale.target(2000, 3000, 1600))
        assertEquals(1599 to 1600, CoverScale.target(1600, 1601, 1600))
    }

    @Test
    fun smallPhotosAreLeftAsTheyAre() {
        assertEquals(1000 to 1500, CoverScale.target(1000, 1500, 1600))
        assertEquals(1600 to 1600, CoverScale.target(1600, 1600, 1600))
        assertEquals(1 to 1, CoverScale.target(1, 1, 1600))
        assertEquals(0 to 0, CoverScale.target(0, 0, 1600))
    }

    @Test
    fun aHugePanoramaStillKeepsAPixel() {
        assertEquals(1600 to 1, CoverScale.target(20000, 3, 1600))
    }

    @Test
    fun aBigPhotoIsSentAsASmallerJpeg() {
        val photo = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 80, 160)) }
        val (jpeg, w, h) = CoverImage.encode(photo)
        assertEquals(1200 to 1600, w to h)
        // JPEG's start-of-image marker.
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(1200, back.width)
        assertEquals(1600, back.height)
        assertTrue("a cover upload should stay small, was ${jpeg.size} bytes", jpeg.size < 1_000_000)
    }

    @Test
    fun aSmallPhotoKeepsItsSize() {
        val photo = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val (_, w, h) = CoverImage.encode(photo)
        assertEquals(400 to 600, w to h)
    }

    @Test
    fun seeThroughPartsBecomeWhite() {
        val photo = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val (jpeg, _, _) = CoverImage.encode(photo)
        val back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val pixel = back.getPixel(20, 30)
        assertTrue("expected white, was ${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    // --- the camera's frame -------------------------------------------------------------------------

    @Test
    fun theFrameMapsOntoThePhotoShownCentreCropped() {
        // A 1080x2400 viewfinder shows a 3000x4000 photo scaled by 0.6 (to fill the height): 1800x2400,
        // 360 px cut off each side.
        val frame = CameraFrame(1080, 2400, left = 240f, top = 600f, right = 840f, bottom = 1500f)
        val crop = CameraCrop.rect(frame, 3000, 4000)
        assertEquals(PixelRect(1000, 1000, 2000, 2500), crop)
    }

    @Test
    fun aFramePastThePhotoIsClampedToIt() {
        // A wide viewfinder over a tall photo: the photo fills the width, top and bottom cut off.
        val frame = CameraFrame(1000, 1000, left = -50f, top = 0f, right = 1050f, bottom = 1000f)
        val crop = CameraCrop.rect(frame, 1000, 2000)
        assertEquals(PixelRect(0, 500, 1000, 1500), crop)
    }

    @Test
    fun aPhotoTurnedAgainstTheViewfinderIsKeptWhole() {
        val frame = CameraFrame(1080, 2400, 240f, 600f, 840f, 1500f)
        assertNull(CameraCrop.rect(frame, 4000, 3000))
        assertNull(CameraCrop.rect(frame.copy(viewWidth = 0), 3000, 4000))
        assertNull(CameraCrop.rect(frame, 0, 0))
    }

    @Test
    fun theGuideIsATwoByThreeBoxInTheMiddle() {
        val frame = guideFrame(androidx.compose.ui.unit.IntSize(1080, 2400), topInset = 300f, bottomInset = 450f)
        val w = frame.right - frame.left
        val h = frame.bottom - frame.top
        assertEquals(1.5f, h / w, 0.001f)
        assertEquals(540f, (frame.left + frame.right) / 2f, 0.5f)
        assertTrue(frame.top >= 300f && frame.bottom <= 2400f - 450f)
    }
}
