//
//  EditorTypingSmokeTests.swift
//  SampleAppiOSUITests
//

import XCTest

/// Smoke test on the iOS simulator (roadmap 0.7): typing on the soft keyboard reaches
/// the editor through Compose's input session, and the editor's text reads back through
/// accessibility. The `ios` CI job runs it.
final class EditorTypingSmokeTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTypingReachesTheEditor() {
        let app = XCUIApplication()
        app.launch()

        app.buttons["Markdown Editor (Blank)"].tap()

        let editor = app.descendants(matching: .any)["Document"]
        XCTAssertTrue(editor.waitForExistence(timeout: 20), "the blank editor did not open")
        editor.tap()

        // Typed into whatever holds keyboard focus: the soft keyboard, or a connected
        // hardware keyboard, which keeps the soft one down.
        app.typeText("Hello")

        let typed = NSPredicate { _, _ in (editor.value as? String)?.contains("Hello") == true }
        let arrived = expectation(for: typed, evaluatedWith: nil)
        let result = XCTWaiter().wait(for: [arrived], timeout: 10)
        XCTAssertEqual(result, .completed, "the editor reads \(String(describing: editor.value))")
    }
}
