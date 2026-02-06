import Capacitor
import CoreNFC
import UIKit

@objc(NfcPlugin)
public class NfcPlugin: CAPPlugin, CAPBridgedPlugin {
    private let pluginVersion: String = "8.0.10"

    public let identifier = "NfcPlugin"
    public let jsName = "CapacitorNfc"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "startScanning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopScanning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "write", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "erase", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "makeReadOnly", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unshare", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]

    private var ndefReaderSession: NFCNDEFReaderSession?
    private var tagReaderSession: NFCTagReaderSession?
    private let sessionQueue = DispatchQueue(label: "app.capgo.nfc.session")
    private var currentTag: NFCNDEFTag?
    private var invalidateAfterFirstRead = true
    private var sessionType: String = "ndef"

    @objc public func startScanning(_ call: CAPPluginCall) {
        #if targetEnvironment(simulator)
        call.reject("NFC is not available on the simulator.", "NO_NFC")
        return
        #else
        guard NFCNDEFReaderSession.readingAvailable else {
            call.reject("NFC is not available on this device.", "NO_NFC")
            return
        }

        invalidateAfterFirstRead = call.getBool("invalidateAfterFirstRead", true)
        let alertMessage = call.getString("alertMessage")
        sessionType = call.getString("iosSessionType", "ndef")

        DispatchQueue.main.async {
            // Invalidate any existing sessions
            self.ndefReaderSession?.invalidate()
            self.ndefReaderSession = nil
            self.tagReaderSession?.invalidate()
            self.tagReaderSession = nil

            if self.sessionType == "tag" {
                // Use NFCTagReaderSession for raw tag support
                self.tagReaderSession = NFCTagReaderSession(
                    pollingOption: [.iso14443, .iso15693, .iso18092],
                    delegate: self,
                    queue: self.sessionQueue
                )
                if let alertMessage, !alertMessage.isEmpty {
                    self.tagReaderSession?.alertMessage = alertMessage
                }
                self.tagReaderSession?.begin()
            } else {
                // Use NFCNDEFReaderSession (default behavior)
                self.ndefReaderSession = NFCNDEFReaderSession(
                    delegate: self,
                    queue: self.sessionQueue,
                    invalidateAfterFirstRead: self.invalidateAfterFirstRead
                )
                if let alertMessage, !alertMessage.isEmpty {
                    self.ndefReaderSession?.alertMessage = alertMessage
                }
                self.ndefReaderSession?.begin()
            }
        }

        call.resolve()
        #endif
    }

    @objc public func stopScanning(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.ndefReaderSession?.invalidate()
            self.ndefReaderSession = nil
            self.tagReaderSession?.invalidate()
            self.tagReaderSession = nil
            self.currentTag = nil
        }
        call.resolve()
    }

    @objc public func write(_ call: CAPPluginCall) {
        guard let tag = currentTag else {
            call.reject("No active NFC session or tag. Call startScanning and present a tag before writing.")
            return
        }

        guard let rawRecords = call.getArray("records") as? [[String: Any]] else {
            call.reject("records is required and must be an array.")
            return
        }

        do {
            let message = try buildMessage(from: rawRecords)
            
            // For NFCTagReaderSession, we need a reference to the session
            if let tagSession = tagReaderSession {
                performWriteWithTagSession(message: message, on: tag, session: tagSession, call: call)
            } else if let ndefSession = ndefReaderSession {
                performWrite(message: message, on: tag, session: ndefSession, call: call)
            } else {
                call.reject("No active NFC session.")
            }
        } catch {
            call.reject("Invalid NDEF records payload.", nil, error)
        }
    }

    @objc public func erase(_ call: CAPPluginCall) {
        guard let tag = currentTag else {
            call.reject("No active NFC session or tag. Call startScanning and present a tag before erasing.")
            return
        }

        let emptyRecord = NFCNDEFPayload(format: .empty, type: Data(), identifier: Data(), payload: Data())
        let message = NFCNDEFMessage(records: [emptyRecord])
        
        if let tagSession = tagReaderSession {
            performWriteWithTagSession(message: message, on: tag, session: tagSession, call: call)
        } else if let ndefSession = ndefReaderSession {
            performWrite(message: message, on: tag, session: ndefSession, call: call)
        } else {
            call.reject("No active NFC session.")
        }
    }

    @objc public func makeReadOnly(_ call: CAPPluginCall) {
        call.reject("Making tags read only is not supported on iOS.", "UNSUPPORTED")
    }

    @objc public func share(_ call: CAPPluginCall) {
        call.reject("Peer-to-peer NFC sharing is not available on iOS.", "UNSUPPORTED")
    }

    @objc public func unshare(_ call: CAPPluginCall) {
        call.reject("Peer-to-peer NFC sharing is not available on iOS.", "UNSUPPORTED")
    }

    @objc public func getStatus(_ call: CAPPluginCall) {
        let status = NFCNDEFReaderSession.readingAvailable ? "NFC_OK" : "NO_NFC"
        call.resolve([
            "status": status
        ])
    }

    @objc public func showSettings(_ call: CAPPluginCall) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            call.reject("Unable to open application settings.")
            return
        }

        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        call.resolve()
    }

    @objc public func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve([
            "version": pluginVersion
        ])
    }

    private func performWrite(message: NFCNDEFMessage, on tag: NFCNDEFTag, session: NFCNDEFReaderSession, call: CAPPluginCall) {
        session.connect(to: tag) { [weak self] error in
            guard let self else {
                DispatchQueue.main.async { call.reject("Session is no longer available.") }
                return
            }

            if let error {
                DispatchQueue.main.async {
                    call.reject("Failed to connect to tag.", nil, error)
                }
                return
            }

            tag.queryNDEFStatus { status, capacity, statusError in
                if let statusError {
                    DispatchQueue.main.async {
                        call.reject("Failed to query tag status.", nil, statusError)
                    }
                    return
                }

                switch status {
                case .readWrite:
                    if capacity < message.length {
                        DispatchQueue.main.async {
                            call.reject("Tag capacity is insufficient for the provided message.")
                        }
                        return
                    }
                    tag.writeNDEF(message) { writeError in
                        DispatchQueue.main.async {
                            if let writeError {
                                call.reject("Failed to write NDEF message.", nil, writeError)
                            } else {
                                call.resolve()
                            }
                        }
                    }
                case .readOnly:
                    DispatchQueue.main.async {
                        call.reject("Tag is read only.")
                    }
                case .notSupported:
                    DispatchQueue.main.async {
                        call.reject("Tag does not support NDEF.")
                    }
                @unknown default:
                    DispatchQueue.main.async {
                        call.reject("Unknown tag status.")
                    }
                }
            }
        }
    }

    private func performWriteWithTagSession(message: NFCNDEFMessage, on tag: NFCNDEFTag, session: NFCTagReaderSession, call: CAPPluginCall) {
        // For NFCTagReaderSession, we need to connect differently
        // The tag should already be connected from the discovery, but we ensure it
        tag.queryNDEFStatus { status, capacity, statusError in
            if let statusError {
                DispatchQueue.main.async {
                    call.reject("Failed to query tag status.", nil, statusError)
                }
                return
            }

            switch status {
            case .readWrite:
                if capacity < message.length {
                    DispatchQueue.main.async {
                        call.reject("Tag capacity is insufficient for the provided message.")
                    }
                    return
                }
                tag.writeNDEF(message) { writeError in
                    DispatchQueue.main.async {
                        if let writeError {
                            call.reject("Failed to write NDEF message.", nil, writeError)
                        } else {
                            call.resolve()
                        }
                    }
                }
            case .readOnly:
                DispatchQueue.main.async {
                    call.reject("Tag is read only.")
                }
            case .notSupported:
                DispatchQueue.main.async {
                    call.reject("Tag does not support NDEF.")
                }
            @unknown default:
                DispatchQueue.main.async {
                    call.reject("Unknown tag status.")
                }
            }
        }
    }

    private func buildMessage(from records: [[String: Any]]) throws -> NFCNDEFMessage {
        if records.isEmpty {
            throw NfcPluginError.invalidPayload
        }

        let payloads = try records.map { record -> NFCNDEFPayload in
            guard let tnfValue = record["tnf"] as? NSNumber,
                  let typeArray = record["type"],
                  let idArray = record["id"],
                  let payloadArray = record["payload"] else {
                throw NfcPluginError.invalidPayload
            }

            let payload = NFCNDEFPayload(
                format: NFCTypeNameFormat(rawValue: UInt8(truncating: tnfValue)) ?? .unknown,
                type: data(from: typeArray),
                identifier: data(from: idArray),
                payload: data(from: payloadArray)
            )
            return payload
        }

        return NFCNDEFMessage(records: payloads)
    }

    private func data(from any: Any) -> Data {
        guard let numbers = any as? [NSNumber] else {
            return Data()
        }
        var bytes = [UInt8]()
        bytes.reserveCapacity(numbers.count)
        numbers.forEach { number in
            bytes.append(number.uint8Value)
        }
        return Data(bytes)
    }

    private func array(from data: Data?) -> [NSNumber]? {
        guard let data else {
            return nil
        }
        return data.map { NSNumber(value: $0) }
    }

    private func notify(event: [String: Any]) {
        DispatchQueue.main.async {
            self.notifyListeners("nfcEvent", data: event, retainUntilConsumed: true)
            guard let type = event["type"] as? String else {
                return
            }
            switch type {
            case "ndef":
                self.notifyListeners("ndefDiscovered", data: event, retainUntilConsumed: true)
            default:
                self.notifyListeners("tagDiscovered", data: event, retainUntilConsumed: true)
            }
        }
    }

    private func buildEvent(tag: NFCNDEFTag, status: NFCNDEFStatus, capacity: Int, message: NFCNDEFMessage?) -> [String: Any] {
        var tagInfo: [String: Any] = [:]
        if let identifierData = extractIdentifier(from: tag) {
            tagInfo["id"] = array(from: identifierData)
        }
        tagInfo["techTypes"] = detectTechTypes(for: tag)
        tagInfo["isWritable"] = status == .readWrite
        tagInfo["maxSize"] = capacity
        tagInfo["type"] = translateType(for: tag)

        if let message {
            tagInfo["ndefMessage"] = message.records.map { record in
                [
                    "tnf": NSNumber(value: record.typeNameFormat.rawValue),
                    "type": array(from: record.type),
                    "id": array(from: record.identifier),
                    "payload": array(from: record.payload)
                ].compactMapValues { $0 }
            }
        }

        return [
            "type": "ndef",
            "tag": tagInfo
        ]
    }

    private func extractIdentifier(from tag: NFCNDEFTag) -> Data? {
        if let miFare = tag as? NFCMiFareTag {
            return miFare.identifier
        }
        if let iso7816 = tag as? NFCISO7816Tag {
            return iso7816.identifier
        }
        if let iso15693 = tag as? NFCISO15693Tag {
            return iso15693.identifier
        }
        if let feliCa = tag as? NFCFeliCaTag {
            return Data(feliCa.currentIDm)
        }
        return nil
    }

    private func detectTechTypes(for tag: NFCNDEFTag) -> [String] {
        var types: [String] = []
        if tag is NFCMiFareTag {
            types.append("NFCMiFareTag")
        }
        if tag is NFCISO7816Tag {
            types.append("NFCISO7816Tag")
        }
        if tag is NFCISO15693Tag {
            types.append("NFCISO15693Tag")
        }
        if tag is NFCFeliCaTag {
            types.append("NFCFeliCaTag")
        }
        return types
    }

    private func translateType(for tag: NFCNDEFTag) -> String? {
        if let miFare = tag as? NFCMiFareTag {
            switch miFare.mifareFamily {
            case .plus:
                return "MIFARE Plus"
            case .ultralight:
                return "MIFARE Ultralight"
            case .desfire:
                return "MIFARE DESFire"
            case .unknown:
                return "MIFARE"
            @unknown default:
                return "MIFARE"
            }
        }
        if tag is NFCISO7816Tag {
            return "ISO 7816"
        }
        if tag is NFCISO15693Tag {
            return "ISO 15693"
        }
        if tag is NFCFeliCaTag {
            return "FeliCa"
        }
        return nil
    }
}

extension NfcPlugin: NFCNDEFReaderSessionDelegate {
    public func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        currentTag = nil
        if (error as NSError).code != NFCReaderError.readerSessionInvalidationErrorFirstNDEFTagRead.rawValue {
            DispatchQueue.main.async {
                let payload: [String: Any] = [
                    "status": NFCNDEFReaderSession.readingAvailable ? "NFC_OK" : "NO_NFC",
                    "enabled": NFCNDEFReaderSession.readingAvailable
                ]
                self.notifyListeners("nfcStateChange", data: payload, retainUntilConsumed: true)
            }
        }
        readerSession = nil
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didDetect tags: [NFCNDEFTag]) {
        guard let tag = tags.first else {
            return
        }

        session.connect(to: tag) { [weak self] error in
            guard let self else {
                return
            }

            if let error {
                session.invalidate(errorMessage: "Failed to connect to the tag: \(error.localizedDescription)")
                return
            }

            tag.queryNDEFStatus { status, capacity, statusError in
                if let statusError {
                    session.invalidate(errorMessage: "Failed to read tag status: \(statusError.localizedDescription)")
                    return
                }

                tag.readNDEF { message, readError in
                    if let readError {
                        session.invalidate(errorMessage: "Failed to read NDEF message: \(readError.localizedDescription)")
                        return
                    }

                    self.currentTag = tag
                    let event = self.buildEvent(tag: tag, status: status, capacity: capacity, message: message)
                    self.notify(event: event)
                }
            }
        }
    }

    public func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {
        guard !messages.isEmpty else {
            return
        }
        let event: [String: Any] = [
            "type": "ndef",
            "tag": [
                "ndefMessage": messages.first?.records.map { record in
                    [
                        "tnf": NSNumber(value: record.typeNameFormat.rawValue),
                        "type": array(from: record.type) ?? [],
                        "id": array(from: record.identifier) ?? [],
                        "payload": array(from: record.payload) ?? []
                    ]
                } ?? []
            ]
        ]
        notify(event: event)
    }
}

// MARK: - NFCTagReaderSessionDelegate
extension NfcPlugin: NFCTagReaderSessionDelegate {
    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        // Session became active, ready to detect tags
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        currentTag = nil
        let nfcError = error as NSError
        
        // Don't emit state change for normal session completion
        if nfcError.code != NFCReaderError.readerSessionInvalidationErrorFirstNDEFTagRead.rawValue &&
           nfcError.code != NFCReaderError.readerSessionInvalidationErrorUserCanceled.rawValue {
            DispatchQueue.main.async {
                let payload: [String: Any] = [
                    "status": NFCNDEFReaderSession.readingAvailable ? "NFC_OK" : "NO_NFC",
                    "enabled": NFCNDEFReaderSession.readingAvailable
                ]
                self.notifyListeners("nfcStateChange", data: payload, retainUntilConsumed: true)
            }
        }
        tagReaderSession = nil
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let firstTag = tags.first else {
            return
        }

        session.connect(to: firstTag) { [weak self] error in
            guard let self else {
                return
            }

            if let error {
                session.invalidate(errorMessage: "Failed to connect to the tag: \(error.localizedDescription)")
                return
            }

            // Handle different tag types
            switch firstTag {
            case .miFare(let mifareTag):
                self.handleMiFareTag(mifareTag, session: session)
            case .iso7816(let iso7816Tag):
                self.handleISO7816Tag(iso7816Tag, session: session)
            case .iso15693(let iso15693Tag):
                self.handleISO15693Tag(iso15693Tag, session: session)
            case .feliCa(let feliCaTag):
                self.handleFeliCaTag(feliCaTag, session: session)
            @unknown default:
                session.invalidate(errorMessage: "Unsupported tag type")
            }
        }
    }

    private func handleMiFareTag(_ mifareTag: NFCMiFareTag, session: NFCTagReaderSession) {
        readNDEFFromTag(mifareTag, session: session)
    }

    private func handleISO7816Tag(_ iso7816Tag: NFCISO7816Tag, session: NFCTagReaderSession) {
        readNDEFFromTag(iso7816Tag, session: session)
    }

    private func handleISO15693Tag(_ iso15693Tag: NFCISO15693Tag, session: NFCTagReaderSession) {
        readNDEFFromTag(iso15693Tag, session: session)
    }

    private func handleFeliCaTag(_ feliCaTag: NFCFeliCaTag, session: NFCTagReaderSession) {
        readNDEFFromTag(feliCaTag, session: session)
    }

    private func readNDEFFromTag(_ ndefTag: NFCNDEFTag, session: NFCTagReaderSession) {
        // Try to read NDEF if available
        ndefTag.queryNDEFStatus { [weak self] status, capacity, error in
            guard let self else {
                return
            }

            if error == nil && status != .notSupported {
                // Tag supports NDEF, try to read it
                ndefTag.readNDEF { [weak self] message, readError in
                    guard let self else {
                        return
                    }

                    if let readError {
                        // NDEF read failed, still emit tag with UID
                        self.emitTagEvent(ndefTag: ndefTag, message: nil, session: session)
                    } else {
                        // Successfully read NDEF
                        self.currentTag = ndefTag
                        let event = self.buildEvent(tag: ndefTag, status: status, capacity: capacity, message: message)
                        self.notify(event: event)
                        if self.invalidateAfterFirstRead {
                            session.invalidate()
                        }
                    }
                }
            } else {
                // Tag doesn't support NDEF or query failed - just emit UID
                self.emitTagEvent(ndefTag: ndefTag, message: nil, session: session)
            }
        }
    }

    private func emitTagEvent(ndefTag: NFCNDEFTag, message: NFCNDEFMessage?, session: NFCTagReaderSession) {
        // Save the current tag for writing
        currentTag = ndefTag

        var tagInfo: [String: Any] = [:]
        
        // Extract and add the tag ID (UID)
        if let identifierData = extractIdentifier(from: ndefTag) {
            tagInfo["id"] = array(from: identifierData)
        }
        
        tagInfo["techTypes"] = detectTechTypes(for: ndefTag)
        tagInfo["type"] = translateType(for: ndefTag)

        if let message {
            tagInfo["isWritable"] = true
            tagInfo["ndefMessage"] = message.records.map { record in
                [
                    "tnf": NSNumber(value: record.typeNameFormat.rawValue),
                    "type": array(from: record.type),
                    "id": array(from: record.identifier),
                    "payload": array(from: record.payload)
                ].compactMapValues { $0 }
            }
        }

        let event: [String: Any] = [
            "type": message != nil ? "ndef" : "tag",
            "tag": tagInfo
        ]
        
        notify(event: event)
        
        if invalidateAfterFirstRead {
            session.invalidate()
        }
    }
}

enum NfcPluginError: Error {
    case invalidPayload
}
