package com.darkrockstudios.texteditor.input

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope

/**
 * What an action gets to work with. The clipboard and the scope belong to the
 * composition rather than to the editor, so they arrive per invocation instead
 * of being captured when the action is registered. Build one per invocation: it
 * also records the editor the action was run on.
 */
class EditorActionContext(
	val state: TextEditorState,
	val clipboard: Clipboard,
	val scope: CoroutineScope,
) {
	/**
	 * The editor the action was run on: the one whose menu or semantics ran it, else the
	 * focused one. Not observed: building a context is no reason to recompose on focus.
	 */
	private val target = Snapshot.withoutReadObservation { state.answeringEditor }

	/**
	 * Runs [block] as aimed at the editor the action was run on, so its edits follow that
	 * editor's line limit and default action, as the action's own call does. For an edit
	 * made after the action suspends, by when focus can have moved to another editor
	 * sharing [state]; call it on the dispatcher that edits the document. With no editor
	 * focused when the action ran, the edit follows the one answering then.
	 */
	fun <T> asTarget(block: () -> T): T = state.asEditor(target, block)
}

/**
 * The implementation of one [EditorCommand.Action].
 *
 * [isEnabled] is for menus and toolbars that grey items out. Key dispatch
 * ignores it: a bound chord must consume its event even with nothing to do,
 * or Ctrl+C with an empty selection would type a literal `c`.
 */
class EditorActionSpec(
	val action: EditorCommand.Action,
	val isEnabled: (EditorActionContext) -> Boolean = { true },
	val perform: (EditorActionContext) -> Unit,
) {
	/**
	 * Whether this mutates the document, which is what a read-only editor refuses.
	 * Built-in ids answer from the built-in constant, not [action]: identity is
	 * the id alone, so `Action("editor.paste", isEdit = false)` must not get to
	 * edit a disabled editor on its own say-so.
	 */
	internal val editsDocument: Boolean
		get() = EditorCommand.Action.builtinFor(action.id)?.isEdit ?: action.isEdit
}

/**
 * Everything this editor can do, keyed by [EditorCommand.Action.id]. Lives on
 * [TextEditorState] because that is the one object every input surface shares,
 * so an action is implemented once no matter how it is triggered. The built-ins
 * register on construction through the same [register] a host calls.
 */
class EditorActionRegistry {
	private val specs = mutableMapOf<String, EditorActionSpec>()

	init {
		registerBuiltinActions()
	}

	/**
	 * Registers [spec]. A previously registered id is replaced, which is how a
	 * host substitutes its own implementation of a built-in.
	 */
	fun register(spec: EditorActionSpec) {
		specs[spec.action.id] = spec
	}

	/**
	 * Drops [action], leaving its chords unconsumed: a printable key types its
	 * character, a Ctrl or Cmd chord goes dead, Tab moves focus out of the editor.
	 * Bind the chord to a no-op action instead if you need it inert.
	 */
	fun unregister(action: EditorCommand.Action) {
		specs.remove(action.id)
	}

	operator fun get(action: EditorCommand.Action): EditorActionSpec? = specs[action.id]

	operator fun contains(action: EditorCommand.Action): Boolean = action.id in specs

	/** The ids currently registered, for diagnostics and tests. */
	val registeredIds: Set<String> get() = specs.keys.toSet()
}
