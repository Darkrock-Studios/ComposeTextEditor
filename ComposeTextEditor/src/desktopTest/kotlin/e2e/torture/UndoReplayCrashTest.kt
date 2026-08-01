package e2e.torture

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import utils.FuzzOp
import utils.StateFuzzInterpreter
import kotlin.test.Test

/**
 * Replays a minimized fuzz script (EditorStateFuzzTest seed 42, greedily shrunk
 * to 19 ops) that crashes the editor with a StringIndexOutOfBoundsException from
 * captureMetadata while undoing a Replace: the undo recomputes the replaced
 * range against a document that has since moved. No document produced by
 * ordinary typing, selecting, and undoing may ever throw.
 */
class UndoReplayCrashTest {

	@Test
	fun `an undo storm over select all replacements must not crash`() {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val markdown = MarkdownExtension(state)
		markdown.importMarkdown("seed line\nsecond line")
		val interpreter = StateFuzzInterpreter(state, markdown, skipDemotions = true)

		val script = listOf(
			FuzzOp.SelectAllType("日本"),
			FuzzOp.TypeText("beta"),
			FuzzOp.TypeText("editor"),
			FuzzOp.TypeText("beta"),
			FuzzOp.TypeText("beta"),
			FuzzOp.TypeText("words"),
			FuzzOp.TypeText("words"),
			FuzzOp.Enter,
			FuzzOp.TypeText("delta"),
			FuzzOp.TypeText("a b"),
			FuzzOp.SelectAllType("x"),
			FuzzOp.TypeText("café"),
			FuzzOp.TypeText("café"),
			FuzzOp.TypeText("café"),
			FuzzOp.UndoBurst(5),
			FuzzOp.PastePlain("café"),
			FuzzOp.SelectRange(107, 599),
			FuzzOp.TypeText("gamma"),
			FuzzOp.UndoBurst(5),
		)

		script.forEach { interpreter.apply(it) }
	}
}
