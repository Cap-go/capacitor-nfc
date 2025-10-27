import XCTest

@testable import NfcPlugin

final class NfcPluginTests: XCTestCase {
    func testPluginCanBeInitialised() {
        let plugin = NfcPlugin()
        XCTAssertNotNil(plugin)
    }
}
