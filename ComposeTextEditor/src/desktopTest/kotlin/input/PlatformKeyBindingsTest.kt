package input

import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.WindowsKeyBindings
import com.darkrockstudios.texteditor.input.keyBindingsForOs
import com.darkrockstudios.texteditor.input.platformKeyBindings
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
	fun `windows host names select the windows bindings`() {
		for (osName in listOf("Windows 11", "Windows 10", "Windows Server 2022")) {
			assertSame(WindowsKeyBindings, keyBindingsForOs(osName), "os.name '$osName'")
		}
	}

	@Test
	fun `other host names select the ctrl bindings`() {
		for (osName in listOf("Linux", "FreeBSD", "SunOS", "")) {
			assertSame(CtrlKeyBindings, keyBindingsForOs(osName), "os.name '$osName'")
		}
	}

	@Test
	fun `the host actual returns one of the known tables`() {
		val bindings = platformKeyBindings()
		assertTrue(
			bindings === CtrlKeyBindings || bindings === WindowsKeyBindings || bindings === MacKeyBindings,
			"platformKeyBindings() returned an unknown table: $bindings",
		)
	}
}
