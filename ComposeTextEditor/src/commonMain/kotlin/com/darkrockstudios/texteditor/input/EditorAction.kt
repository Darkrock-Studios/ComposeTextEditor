package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope

/**
 * What an action gets to work with. The clipboard and the scope belong to the
 * composition rather than to the editor, so they arrive per invocation instead
 * of being captured when the action is registered.
 */
class EditorActionContext(
	val state: TextEditorState,
	val clipboard: Clipboard,
	val scope: CoroutineScope,
)

/**
 * The implementation of one [EditorCommand.Action].
 *
 * [isEnabled] reports whether the action would currently do anything, for menus
 * and toolbars that grey their items out. Key dispatch ignores it on purpose: a
 * chord that resolves to a registered action must consume the key event whether
 * or not the action has work to do, or Ctrl+C with an empty selection would
 * fall through and type a literal `c`.
 */
class EditorActionSpec(
	val action: EditorCommand.Action,
	val isEnabled: (EditorActionContext) -> Boolean = { true },
	val perform: (EditorActionContext) -> Unit,
)

/**
 * Everything this editor can do, keyed by [EditorCommand.Action.id]. Lives on
 * [TextEditorState] because that is the one object every input surface already
 * shares: key chords reach it through [KeyBindings] and
 * [TextEditorKeyCommandHandler], and the context menu resolves through it too,
 * so an action is implemented once no matter how many ways it can be triggered.
 *
 * The built-ins register on construction through the same [register] a host
 * calls, so there is no private door for library actions.
 */
class EditorActionRegistry {
	private val specs = mutableMapOf<String, EditorActionSpec>()

	init {
		registerBuiltinActions()
	}

	/**
	 * Registers [spec]. An id that is already registered is replaced, which is
	 * how a host substitutes its own implementation of a built-in (binding
	 * `editor.paste` to a paste that strips formatting, say).
	 */
	fun register(spec: EditorActionSpec) {
		specs[spec.action.id] = spec
	}

	/**
	 * Drops [action], after which the editor stops consuming any chord bound to it
	 * and the event falls through to whatever would have seen it otherwise. What
	 * that means depends on the chord: an unmodified printable key types its
	 * character, a Ctrl or Cmd chord does nothing at all, and Tab reaches the
	 * platform's focus traversal and moves focus out of the editor. Bind the chord
	 * to an action of your own rather than unregistering if you need it inert.
	 */
	fun unregister(action: EditorCommand.Action) {
		specs.remove(action.id)
	}

	operator fun get(action: EditorCommand.Action): EditorActionSpec? = specs[action.id]

	operator fun contains(action: EditorCommand.Action): Boolean = action.id in specs

	/** The ids currently registered, for diagnostics and tests. */
	val registeredIds: Set<String> get() = specs.keys.toSet()
}
