package io.github.glaforge.jixoo.controller

import io.github.glaforge.jixoo.controller.gcs.GcsCacheManager
import io.github.glaforge.jixoo.controller.image.ImageFrameProcessor
import io.github.glaforge.jixoo.controller.model.SlideshowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowConfigTest {

    @Test
    fun testParseDuration() {
        assertEquals(60, SlideshowConfig.parseDuration("1m"))
        assertEquals(300, SlideshowConfig.parseDuration("5M"))
        assertEquals(90, SlideshowConfig.parseDuration("90s"))
        assertEquals(45, SlideshowConfig.parseDuration("45S"))
        assertEquals(60, SlideshowConfig.parseDuration("60"))
        assertEquals(60, SlideshowConfig.parseDuration(""))
    }

    @Test
    fun testSplitCommaSeparatedIps() {
        val (p1, s1) = SlideshowConfig.splitCommaSeparatedIps("192.168.1.10,192.168.1.11")
        assertEquals("192.168.1.10", p1)
        assertEquals("192.168.1.11", s1)

        val (p2, s2) = SlideshowConfig.splitCommaSeparatedIps("192.168.1.49")
        assertEquals("192.168.1.49", p2)
        assertNull(s2)
    }

    @Test
    fun testParseGcsUrl() {
        val loc = GcsCacheManager.parseGcsUrl("gs://conference-pics/gravidots/visuals")
        assertNotNull(loc)
        assertEquals("conference-pics", loc!!.bucket)
        assertEquals("gravidots/visuals/", loc.prefix)

        val rootLoc = GcsCacheManager.parseGcsUrl("gs://my-bucket")
        assertNotNull(rootLoc)
        assertEquals("my-bucket", rootLoc!!.bucket)
        assertEquals("", rootLoc.prefix)
    }

    @Test
    fun testValidImageFilename() {
        assertTrue(ImageFrameProcessor.isValidImageFilename("anim.GIF"))
        assertTrue(ImageFrameProcessor.isValidImageFilename("photo.jpg"))
        assertTrue(ImageFrameProcessor.isValidImageFilename("vector.svg"))
        assertTrue(ImageFrameProcessor.isValidImageFilename("pic.webp"))
        assertFalse(ImageFrameProcessor.isValidImageFilename("readme.txt"))
    }
}
