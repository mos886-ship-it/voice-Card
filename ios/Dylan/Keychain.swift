import Foundation
import Security

/// API 金鑰存在 iPhone 的鑰匙圈（照 IOS_PORT.md：不要寫死、不要放進 repo）。只存在這支手機，不同步到 iCloud。
enum Keychain {
    private static let service = "com.example.dylan.keys"

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 空字串 = 刪除。
    @discardableResult
    static func set(_ value: String, for account: String) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { return true }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly // 鎖屏後背景處理也讀得到
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static let geminiKey = "gemini_api_key"
}
