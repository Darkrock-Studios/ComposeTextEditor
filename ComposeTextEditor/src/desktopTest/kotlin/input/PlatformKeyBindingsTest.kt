package input

import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.keyBindingsForOs
import com.darkrockstudios.texteditor.input.platformKeyBindings
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The host detection that decides which chord table ships. The UI harness pins its own
 * bindings, so without this nothing exercises the choice real users get.
 */
class PlatformKeyBindingsTest {

	@Test
	fun `macos host names select the mac bindings`() {
		for (osName in listOf("Mac OS X", "macOS", "Darwin")) {
			assertSame(MacKeyBindings, keyBindingsForOs(osName), "os.name '$osName'")
		}
	}

	@Test
	fun `other host names select the ctrl bindings`() {
		for (osName in listOf("Windows 11", "Windows 10", "Linux", "FreeBSD", "")) {
			assertSame(CtrlKeyBindings, keyBindingsForOs(osName), "os.name '$osName'")
		}
	}

	@Test
	fun `the host actual resolves to one of the two tables`() {
		val bindings = platformKeyBindings()
		assertSame(keyBindingsForOs(System.getProperty("os.name").orEmpty()), bindings)
	}
}
