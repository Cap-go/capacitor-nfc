import XCTest

@testable import NfcPlugin

final class NfcPluginTests: XCTestCase {
    func testPluginCanBeInitialised() {
        let plugin = NfcPlugin()
        XCTAssertNotNil(plugin)
    }

    func testDefaultIosPollingOptionsExcludeFelica() {
        XCTAssertEqual(NfcPlugin.defaultIosPollingOptions, ["iso14443", "iso15693"])
        XCTAssertFalse(NfcPlugin.defaultIosPollingOptions.contains("iso18092"))
    }
}
