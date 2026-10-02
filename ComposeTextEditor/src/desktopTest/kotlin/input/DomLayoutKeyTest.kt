package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.darkrockstudios.texteditor.input.UNICODE_KEY_CODE_BASE
import com.darkrockstudios.texteditor.input.layoutKeyFromCodePoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shortcuts on the web follow the active keyboard layout. Compose web names a key
 * by the DOM `code`, its US QWERTY position, and puts the DOM `key`, the active layout's
 * character, in the code point; a `key` that is a name ("Dead", "ArrowLeft", "F2") gives
 * the key code as the code point instead. These events have that shape, with the web's
 * key codes (the DOM's), which differ from desktop's outside the letters and digits.
 */
@OptIn(InternalComposeUiApi::class)
class DomLayoutKeyTest {

	/** Compose web's `Key` values for the keys these tests press (`Key.web.kt`). */
	private object Web {
		val A = Key(65)
		val B = Key(66)
		val C = Key(67)
		val E = Key(69)
		val F = Key(70)
		val Q = Key(81)
		val V = Key(86)
		val W = Key(87)
		val Y = Key(89)
		val Z = Key(90)
		val One = Key(49)
		val Two = Key(50)
		val Spacebar = Key(32)
		val Enter = Key(13)
		val Insert = Key(45)
		val Delete = Key(46)
		val DirectionLeft = Key(37)
		val Semicolon = Key(59)
		val Equals = Key(61)
		val Comma = Key(188)
		val Slash = Key(191)
		val LeftBracket = Key(219)
		val Apostrophe = Key(222)
		val F1 = Key(112)
		val F11 = Key(122)
		val NumPad1 = Key(97)
		val Unknown = Key(-1)
	}

	private fun press(
		code: Key,
		key: String,
		ctrl: Boolean = false,
		meta: Boolean = false,
		alt: Boolean = false,
		shift: Boolean = false,
		type: KeyEventType = KeyEventType.KeyDown,
	): KeyEvent = KeyEvent(
		key = code,
		type = type,
		codePoint = if (key.length == 1) key[0].code else code.keyCode.toInt(),
		isCtrlPressed = ctrl,
		isMetaPressed = meta,
		isAltPressed = alt,
		isShiftPressed = shift,
	)

	private fun unicode(char: Char): Key = Key((UNICODE_KEY_CODE_BASE + char.code).toLong())

	@Test
	fun `the web numbers the letters as desktop does`() {
		assertEquals(Key.A, Web.A)
		assertEquals(Key.Z, Web.Z)
	}

	@Test
	fun `a letter is the one the active layout types`() {
		// AZERTY: a on QWERTY's Q, z on W.
		assertEquals(Key.A, press(Web.Q, "a", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Key.Z, press(Web.W, "z", ctrl = true).layoutKeyFromCodePoint())
		// QWERTZ: z on Y, y on Z.
		assertEquals(Key.Z, press(Web.Y, "z", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Key.Y, press(Web.Z, "y", ctrl = true).layoutKeyFromCodePoint())
		// Dvorak and BÉPO put letters on punctuation keys: Dvorak's s on ';' and z on '/',
		// BÉPO's z on '['.
		assertEquals(Key.S, press(Web.Semicolon, "s", meta = true).layoutKeyFromCodePoint())
		assertEquals(Key.Z, press(Web.Slash, "z", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Key.Z, press(Web.LeftBracket, "z", ctrl = true).layoutKeyFromCodePoint())
	}

	@Test
	fun `a capital is the same letter`() {
		assertEquals(Key.Z, press(Web.W, "Z", ctrl = true, shift = true).layoutKeyFromCodePoint())
		assertEquals(Key.Z, press(Web.Z, "Z", ctrl = true, shift = true).layoutKeyFromCodePoint())
	}

	@Test
	fun `a key up follows the layout too`() {
		assertEquals(Key.Z, press(Web.W, "z", type = KeyEventType.KeyUp).layoutKeyFromCodePoint())
	}

	/** So on a Latin layout no letter chord lands on two keys. */
	@Test
	fun `a letter key the active layout gives an accented letter or punctuation is that key`() {
		// BÉPO: 'à' on QWERTY's Z, 'é' on W, '.' on V.
		assertEquals(unicode('à'), press(Web.Z, "à", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(unicode('é'), press(Web.W, "é", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(unicode('.'), press(Web.V, ".", ctrl = true).layoutKeyFromCodePoint())
		// Dvorak: ';' on QWERTY's Z, '.' on E, ',' on W.
		assertEquals(unicode(';'), press(Web.Z, ";", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(unicode('.'), press(Web.E, ".", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(unicode(','), press(Web.W, ",", ctrl = true).layoutKeyFromCodePoint())
		// An IPA letter is Latin too.
		assertEquals(unicode('ɛ'), press(Web.E, "ɛ", ctrl = true).layoutKeyFromCodePoint())
	}

	/** Cyrillic, Greek, Hebrew and Thai keep their shortcuts on the QWERTY letters. */
	@Test
	fun `a letter key the active layout gives another script's letter or mark keeps its reported key`() {
		assertEquals(Web.Z, press(Web.Z, "я", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.V, press(Web.V, "ω", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.C, press(Web.C, "ב", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.B, press(Web.B, "ิ", ctrl = true).layoutKeyFromCodePoint())
	}

	/**
	 * AZERTY's digit row types 'é', '&' and the like unshifted. A key that is not a letter
	 * key keeps its name unless it types a Latin letter.
	 */
	@Test
	fun `other keys keep their reported key`() {
		assertEquals(Web.Two, press(Web.Two, "é", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.One, press(Web.One, "&", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.Comma, press(Web.Comma, ";", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.Apostrophe, press(Web.Apostrophe, "-", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.Spacebar, press(Web.Spacebar, " ", ctrl = true).layoutKeyFromCodePoint())
	}

	/**
	 * A named key's code point is its key code, which for some keys is a letter's or a
	 * character's: F1 is 'p', F11 'z', Numpad 1 with Num Lock off ("End") 'a', Insert '-'
	 * and Delete '.'.
	 */
	@Test
	fun `a named key keeps its reported key`() {
		assertEquals(Web.F1, press(Web.F1, "F1").layoutKeyFromCodePoint())
		assertEquals(Web.F11, press(Web.F11, "F11").layoutKeyFromCodePoint())
		assertEquals(Web.NumPad1, press(Web.NumPad1, "End").layoutKeyFromCodePoint())
		assertEquals(Web.Insert, press(Web.Insert, "Insert", shift = true).layoutKeyFromCodePoint())
		assertEquals(Web.Delete, press(Web.Delete, "Delete", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.DirectionLeft, press(Web.DirectionLeft, "ArrowLeft", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.Enter, press(Web.Enter, "Enter", ctrl = true).layoutKeyFromCodePoint())
	}

	/**
	 * Compose gives a dead key the key code as its code point, the same as the key's own
	 * capital, so a letter key the active layout gives a dead key keeps its QWERTY name.
	 */
	@Test
	fun `a dead key keeps its reported key`() {
		assertEquals(Web.Y, press(Web.Y, "Dead", ctrl = true).layoutKeyFromCodePoint())
		assertEquals(Web.Equals, press(Web.Equals, "Dead", ctrl = true).layoutKeyFromCodePoint())
	}

	/**
	 * With Option (macOS) or AltGr (Ctrl+Alt on Windows) held the browser reports that
	 * layer's character, which does not name the key's letter: Option+F types 'ƒ' and
	 * Option+C 'ç'.
	 */
	@Test
	fun `alt keeps a letter key that types no plain latin letter`() {
		assertEquals(Web.F, press(Web.F, "ƒ", meta = true, alt = true).layoutKeyFromCodePoint())
		assertEquals(Web.C, press(Web.C, "ç", meta = true, alt = true).layoutKeyFromCodePoint())
		assertEquals(Web.V, press(Web.V, "◊", meta = true, alt = true, shift = true).layoutKeyFromCodePoint())
		assertEquals(Web.Z, press(Web.Z, "ż", ctrl = true, alt = true).layoutKeyFromCodePoint())
		assertEquals(Key.A, press(Web.Q, "a", meta = true, alt = true).layoutKeyFromCodePoint())
	}

	/** A virtual keyboard sends no `code`, so Compose finds no key; its letter is still the key. */
	@Test
	fun `a key compose cannot name is its letter`() {
		assertEquals(Key.A, press(Web.Unknown, "a").layoutKeyFromCodePoint())
		assertEquals(Web.Unknown, press(Web.Unknown, "<").layoutKeyFromCodePoint())
	}
}
