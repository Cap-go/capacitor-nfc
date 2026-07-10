import CoreNFC
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

    func testSessionEndReasons() {
        XCTAssertEqual(reason(for: NFCReaderError.readerSessionInvalidationErrorUserCanceled.rawValue), "userCancelled")
        XCTAssertEqual(reason(for: NFCReaderError.readerSessionInvalidationErrorSessionTimeout.rawValue), "sessionTimeout")
        XCTAssertEqual(reason(for: -1), "invalidated")
    }

    func testFirstReadCompletionDoesNotHaveSessionEndReason() {
        XCTAssertNil(reason(for: NFCReaderError.readerSessionInvalidationErrorFirstNDEFTagRead.rawValue))
    }

    private func reason(for errorCode: Int) -> String? {
        nfcSessionEndReason(for: NSError(domain: "NfcPluginTests", code: errorCode))
    }
}
