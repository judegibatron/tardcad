package io.github.judegibatron.phoneagent

import io.github.judegibatron.phoneagent.trigger.HoldDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldDetectorParserTest {

    private val labelled = """
        add device 1: /dev/input/event7
          name:     "sec_e-pen"
          events:
            KEY (0001): BTN_TOOL_PEN          BTN_TOUCH
            ABS (0003): ABS_X                 : value 0, min 0, max 14399, fuzz 0, flat 0, resolution 0
                        ABS_Y                 : value 0, min 0, max 31199, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 2: /dev/input/event5
          name:     "sec_touchscreen"
          events:
            KEY (0001): BTN_TOUCH
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 2339, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 3: /dev/input/event1
          name:     "gpio_keys"
          events:
            KEY (0001): KEY_VOLUMEDOWN        KEY_VOLUMEUP
          input props:
            <none>
    """.trimIndent()

    private val hex = """
        add device 1: /dev/input/event3
          name:     "fts_ts"
          events:
            KEY (0001): 014a
            ABS (0003): 002f  : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        0035  : value 0, min 0, max 1439, fuzz 0, flat 0, resolution 0
                        0036  : value 0, min 0, max 3199, fuzz 0, flat 0, resolution 0
                        0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent()

    @Test
    fun `labelled output yields only the multi-touch device`() {
        val devices = HoldDetector.parseGetevent(labelled)
        assertEquals(1, devices.size)
        val touch = devices.single()
        assertEquals("/dev/input/event5", touch.path)
        assertEquals("sec_touchscreen", touch.name)
        assertEquals(1079, touch.maxX)
        assertEquals(2339, touch.maxY)
        assertTrue(touch.direct)
    }

    @Test
    fun `hex output without labels is parsed too`() {
        val devices = HoldDetector.parseGetevent(hex)
        assertEquals(1, devices.size)
        assertEquals("fts_ts", devices[0].name)
        assertEquals(1439, devices[0].maxX)
        assertEquals(3199, devices[0].maxY)
    }

    @Test
    fun `empty output yields nothing`() {
        assertTrue(HoldDetector.parseGetevent("").isEmpty())
    }
}
