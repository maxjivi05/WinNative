#define wmain session_main
#include "../battlenet.c"
#undef wmain

int wmain(int argc, WCHAR **argv) {
    if (argc == 5 && !wcscmp(argv[1], L"--forward")) {
        if (wcscmp(argv[2], L"space separated") || wcscmp(argv[3], L"embedded\"quote") || wcscmp(argv[4], L"trailing\\")) return 30;
        return save_state(AUTH L"forwarded", (const BYTE *)"ok", 2) ? 0 : 31;
    }
    if (argc != 2) return 20;
    BOOL restore = !wcscmp(argv[1], L"--restore");
    BOOL persist = !wcscmp(argv[1], L"--persist");
    if (!restore && !persist && wcscmp(argv[1], L"--exec=launch Fen")) return 20;
    const char *account = "winnative-interoperability-test@example.invalid";
    WCHAR name[9]; swprintf(name, 9, L"%08X", account_hash((const BYTE *)account, strlen(account)));
    BYTE blob[LIMIT]; DWORD size = sizeof(blob); DATA_BLOB entropy = {16, entropy_bytes}, decoded = {0};
    if (!read_registry(name, blob, &size)) return 21;
    DATA_BLOB protected = {size, blob};
    if (!CryptUnprotectData(&protected, NULL, &entropy, NULL, NULL, 1, &decoded)) return 22;
    const char *expected = restore ? "US-11111111-1111-1111-1111-111111111111" : "US-00000000-0000-0000-0000-000000000000";
    BOOL matches = decoded.cbData == strlen(expected) && !memcmp(decoded.pbData, expected, decoded.cbData);
    SecureZeroMemory(decoded.pbData, decoded.cbData); LocalFree(decoded.pbData);
    if (!matches) return 23;
    const WCHAR *state_keys[] = {L"Software\\Blizzard Entertainment\\Battle.net\\Identity", L"Software\\Blizzard Entertainment\\Battle.net\\EncryptionKey"};
    for (int i = 0; i < 2; i++) {
        HKEY state; DWORD value = 0, type = 0, length = sizeof(value);
        if (RegCreateKeyExW(HKEY_CURRENT_USER, state_keys[i], 0, NULL, 0, KEY_ALL_ACCESS, NULL, &state, NULL)) return 28;
        if (restore) {
            LSTATUS r = RegQueryValueExW(state, L"WinNativeTest", NULL, &type, (BYTE *)&value, &length);
            if (r || type != REG_DWORD || value != 42) { RegCloseKey(state); return 29; }
        } else {
            value = 42; RegSetValueExW(state, L"WinNativeTest", 0, REG_DWORD, (BYTE *)&value, sizeof(value));
        }
        RegCloseKey(state);
    }
    status("fixture_import_passed"); Sleep(3000);
    char rotated[] = "US-11111111-1111-1111-1111-111111111111";
    DATA_BLOB plain = {strlen(rotated), (BYTE *)rotated}, updated = {0};
    if (!CryptProtectData(&plain, L"", &entropy, NULL, NULL, 1, &updated)) return 24;
    if (!write_registry(name, updated.pbData, updated.cbData)) return 25;
    LocalFree(updated.pbData); Sleep(3000);
    if (persist) return 0;
    HKEY key;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, KEY, 0, KEY_SET_VALUE, &key)) return 26;
    LSTATUS result = RegDeleteValueW(key, name); RegCloseKey(key);
    return result ? 27 : 0;
}
