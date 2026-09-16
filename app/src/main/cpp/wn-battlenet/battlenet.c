#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <wincrypt.h>
#include <tlhelp32.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define AUTH L"C:\\WinNative\\Battle.net\\auth\\"
#define KEY L"Software\\Blizzard Entertainment\\Battle.net\\UnifiedAuth"
#define LIMIT 8192
static BYTE entropy_bytes[] = {0xc8,0x76,0xf4,0xae,0x4c,0x95,0x2e,0xfe,0xf2,0xfa,0x0f,0x54,0x19,0xc0,0x9c,0x43};

static uint32_t account_hash(const BYTE *p, size_t n) {
    uint32_t h = (uint32_t)n;
    while (n >= 4) {
        uint32_t k; memcpy(&k, p, 4); k *= 0x5bd1e995; k ^= k >> 24; k *= 0x5bd1e995;
        h = h * 0x5bd1e995 ^ k; p += 4; n -= 4;
    }
    if (n == 3) h ^= (uint32_t)p[2] << 16;
    if (n >= 2) h ^= (uint32_t)p[1] << 8;
    if (n >= 1) { h ^= p[0]; h *= 0x5bd1e995; }
    h ^= h >> 13; h *= 0x5bd1e995; return h ^ (h >> 15);
}

static BOOL read_file(const WCHAR *path, BYTE *data, DWORD *size) {
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;
    LARGE_INTEGER length; DWORD read = 0;
    BOOL ok = GetFileSizeEx(file, &length) && length.QuadPart > 0 && length.QuadPart <= *size;
    if (ok) ok = ReadFile(file, data, (DWORD)length.QuadPart, &read, NULL) && read == length.QuadPart;
    CloseHandle(file); if (ok) *size = read; return ok;
}

static BOOL save_session(const BYTE *data, DWORD size) {
    HANDLE file = CreateFileW(AUTH L"session.tmp", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;
    DWORD written; BOOL ok = WriteFile(file, data, size, &written, NULL) && written == size && FlushFileBuffers(file);
    CloseHandle(file);
    if (ok) ok = MoveFileExW(AUTH L"session.tmp", AUTH L"session.bin", MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    if (!ok) DeleteFileW(AUTH L"session.tmp");
    return ok;
}

static BOOL save_state(const WCHAR *path, const BYTE *data, DWORD size) {
    WCHAR temporary[MAX_PATH];
    if (swprintf(temporary, MAX_PATH, L"%ls.tmp", path) < 0) return FALSE;
    HANDLE file = CreateFileW(temporary, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;
    DWORD written = 0;
    BOOL ok = WriteFile(file, data, size, &written, NULL) && written == size && FlushFileBuffers(file);
    CloseHandle(file);
    if (ok) ok = MoveFileExW(temporary, path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    if (!ok) DeleteFileW(temporary);
    return ok;
}

static BOOL registry_state(const WCHAR *key_name, const WCHAR *file, BOOL restore) {
    BYTE bytes[LIMIT], existing[LIMIT]; DWORD size = sizeof(bytes), used = 4;
    HKEY key;
    if (restore) {
        if (!read_file(file, bytes, &size)) return GetFileAttributesW(file) == INVALID_FILE_ATTRIBUTES;
        DWORD count; if (size < 4) return FALSE; memcpy(&count, bytes, 4);
        if (count > 32) return FALSE;
        for (DWORD i = 0; i < count; i++) {
            DWORD name_size, data_size, type;
            if (size - used < 12) return FALSE;
            memcpy(&name_size, bytes + used, 4); memcpy(&data_size, bytes + used + 4, 4); memcpy(&type, bytes + used + 8, 4); used += 12;
            if (name_size < 2 || name_size > 512 || name_size % 2 || data_size > LIMIT ||
                name_size + data_size > size - used || bytes[used + name_size - 1] || bytes[used + name_size - 2] ||
                (type != REG_BINARY && type != REG_SZ && type != REG_DWORD)) return FALSE;
            used += name_size + data_size;
        }
        if (used != size || RegCreateKeyExW(HKEY_CURRENT_USER, key_name, 0, NULL, 0, KEY_SET_VALUE, NULL, &key, NULL)) return FALSE;
        used = 4; BOOL ok = TRUE;
        for (DWORD i = 0; i < count && ok; i++) {
            DWORD name_size, data_size, type;
            memcpy(&name_size, bytes + used, 4); memcpy(&data_size, bytes + used + 4, 4); memcpy(&type, bytes + used + 8, 4); used += 12;
            WCHAR name[256]; memcpy(name, bytes + used, name_size);
            ok = RegSetValueExW(key, name, 0, type, bytes + used + name_size, data_size) == ERROR_SUCCESS;
            used += name_size + data_size;
        }
        RegCloseKey(key); return ok;
    }
    LSTATUS opened = RegOpenKeyExW(HKEY_CURRENT_USER, key_name, 0, KEY_QUERY_VALUE, &key);
    if (opened == ERROR_FILE_NOT_FOUND) return TRUE;
    if (opened) return FALSE;
    DWORD count = 0; BOOL ok = TRUE;
    for (;;) {
        WCHAR name[256]; BYTE data[LIMIT]; DWORD name_chars = 256, data_size = sizeof(data), type;
        LSTATUS result = RegEnumValueW(key, count, name, &name_chars, NULL, &type, data, &data_size);
        if (result == ERROR_NO_MORE_ITEMS) break;
        DWORD name_size = (name_chars + 1) * sizeof(WCHAR);
        if (result || count >= 32 || used + 12 + name_size + data_size > LIMIT ||
            (type != REG_BINARY && type != REG_SZ && type != REG_DWORD)) { ok = FALSE; break; }
        memcpy(bytes + used, &name_size, 4); memcpy(bytes + used + 4, &data_size, 4); memcpy(bytes + used + 8, &type, 4); used += 12;
        memcpy(bytes + used, name, name_size); used += name_size;
        memcpy(bytes + used, data, data_size); used += data_size; count++;
    }
    RegCloseKey(key); if (!ok) return FALSE;
    memcpy(bytes, &count, 4); DWORD previous = sizeof(existing);
    if (read_file(file, existing, &previous) && previous == used && !memcmp(existing, bytes, used)) return TRUE;
    return save_state(file, bytes, used);
}

static BOOL shared_client_state(BOOL restore) {
    return registry_state(L"Software\\Blizzard Entertainment\\Battle.net\\Identity", AUTH L"identity.bin", restore) &&
        registry_state(L"Software\\Blizzard Entertainment\\Battle.net\\EncryptionKey", AUTH L"database-key.bin", restore);
}

static void status(const char *value) {
    HANDLE file = CreateFileW(AUTH L"status", GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_ALWAYS, 0, NULL);
    if (file != INVALID_HANDLE_VALUE) { DWORD n; WriteFile(file, value, (DWORD)strlen(value), &n, NULL); CloseHandle(file); }
}

static BOOL read_registry(const WCHAR *name, BYTE *data, DWORD *size) {
    HKEY key; DWORD type = 0;
    LSTATUS opened = RegOpenKeyExW(HKEY_CURRENT_USER, KEY, 0, KEY_QUERY_VALUE, &key);
    if (opened) { SetLastError(opened); return FALSE; }
    LSTATUS result = RegQueryValueExW(key, name, NULL, &type, data, size);
    RegCloseKey(key);
    BOOL ok = result == ERROR_SUCCESS && type == REG_BINARY && *size > 0 && *size <= LIMIT;
    SetLastError(ok ? ERROR_SUCCESS : result ? result : ERROR_INVALID_DATA); return ok;
}

static BOOL write_registry(const WCHAR *name, const BYTE *data, DWORD size) {
    HKEY key;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, KEY, 0, NULL, 0, KEY_SET_VALUE, NULL, &key, NULL)) return FALSE;
    LSTATUS result = RegSetValueExW(key, name, 0, REG_BINARY, data, size);
    if (!result) result = RegFlushKey(key);
    RegCloseKey(key); return result == ERROR_SUCCESS;
}

static BOOL import_session(const WCHAR *name) {
    BYTE data[LIMIT]; DWORD size = 2048;
    DATA_BLOB entropy = {sizeof(entropy_bytes), entropy_bytes}, result = {0};
    if (GetFileAttributesW(AUTH L"pending.token") != INVALID_FILE_ATTRIBUTES) {
        if (!read_file(AUTH L"pending.token", data, &size)) return FALSE;
        DATA_BLOB plain = {size, data};
        BOOL ok = CryptProtectData(&plain, L"", &entropy, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &result);
        SecureZeroMemory(data, sizeof(data));
        if (ok) ok = save_session(result.pbData, result.cbData) && write_registry(name, result.pbData, result.cbData);
        if (result.pbData) LocalFree(result.pbData);
        if (ok) ok = DeleteFileW(AUTH L"pending.token");
        return ok;
    }
    size = sizeof(data);
    if (!read_file(AUTH L"session.bin", data, &size)) {
        if (GetFileAttributesW(AUTH L"session.bin") != INVALID_FILE_ATTRIBUTES) return FALSE;
        HKEY key;
        LSTATUS opened = RegOpenKeyExW(HKEY_CURRENT_USER, KEY, 0, KEY_SET_VALUE, &key);
        if (opened == ERROR_FILE_NOT_FOUND) return TRUE;
        if (opened) return FALSE;
        LSTATUS removed = RegDeleteValueW(key, name); RegCloseKey(key);
        return removed == ERROR_SUCCESS || removed == ERROR_FILE_NOT_FOUND;
    }
    DATA_BLOB protected = {size, data};
    BOOL ok = CryptUnprotectData(&protected, NULL, &entropy, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &result);
    if (result.pbData) { SecureZeroMemory(result.pbData, result.cbData); LocalFree(result.pbData); }
    if (ok) ok = write_registry(name, data, size);
    SecureZeroMemory(data, sizeof(data)); return ok;
}

static BOOL client_running(void) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return TRUE;
    PROCESSENTRY32W entry; entry.dwSize = sizeof(entry); BOOL found = FALSE;
    for (BOOL next = Process32FirstW(snapshot, &entry); next; next = Process32NextW(snapshot, &entry)) {
        if (!_wcsicmp(entry.szExeFile, L"Battle.net.exe") || !_wcsicmp(entry.szExeFile, L"Battle.net Launcher.exe") ||
            !_wcsicmp(entry.szExeFile, L"Battle.net-Setup.exe")) { found = TRUE; break; }
    }
    CloseHandle(snapshot); return found;
}

static BOOL append_arg(WCHAR *command, size_t *used, const WCHAR *arg) {
    size_t n = 0; if (*used + wcslen(arg) * 2 + 4 >= 32767) return FALSE;
    if (*used) command[(*used)++] = L' ';
    command[(*used)++] = L'"';
    for (; *arg; arg++) {
        if (*arg == L'\\') { n++; continue; }
        for (size_t i = 0; i < n * (*arg == L'"' ? 2 : 1); i++) command[(*used)++] = L'\\';
        n = 0; if (*arg == L'"') command[(*used)++] = L'\\'; command[(*used)++] = *arg;
    }
    for (size_t i = 0; i < n * 2; i++) command[(*used)++] = L'\\';
    command[(*used)++] = L'"'; command[*used] = 0; return TRUE;
}

int wmain(int argc, WCHAR **argv) {
    if (argc < 2) return 2;
    HANDLE mutex = CreateMutexW(NULL, TRUE, L"Local\\WinNative.BattleNet.Auth");
    if (!mutex) return 3;
    BOOL owner = GetLastError() != ERROR_ALREADY_EXISTS;
    HANDLE ready = CreateEventW(NULL, TRUE, FALSE, L"Local\\WinNative.BattleNet.Ready");
    if (!ready || (!owner && WaitForSingleObject(ready, 30000) != WAIT_OBJECT_0)) {
        if (ready) CloseHandle(ready);
        if (owner) ReleaseMutex(mutex);
        CloseHandle(mutex); return 7;
    }
    WCHAR name[9] = {0}; BYTE account[1025] = {0}; DWORD account_size = 1024;
    BOOL has_account = read_file(AUTH L"account", account, &account_size);
    if (owner && has_account) {
        if (!shared_client_state(TRUE)) { status("state_import_failed"); CloseHandle(ready); ReleaseMutex(mutex); CloseHandle(mutex); return 8; }
        swprintf(name, 9, L"%08X", account_hash(account, account_size));
        if (!import_session(name)) { status("credential_import_failed"); CloseHandle(ready); ReleaseMutex(mutex); CloseHandle(mutex); return 4; }
    }
    if (owner) SetEvent(ready);
    SecureZeroMemory(account, sizeof(account));
    BYTE previous[LIMIT], current[LIMIT]; DWORD previous_size = sizeof(previous);
    BOOL had_token = owner && has_account && read_registry(name, previous, &previous_size);
    WCHAR *command = HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, 32767 * sizeof(WCHAR));
    if (!command) { CloseHandle(ready); if (owner) ReleaseMutex(mutex); CloseHandle(mutex); return 5; }
    size_t used = 0; BOOL valid = TRUE;
    for (int i = 1; i < argc && valid; i++) valid = append_arg(command, &used, argv[i]);
    STARTUPINFOW startup = {0}; startup.cb = sizeof(startup); PROCESS_INFORMATION process = {0};
    BOOL launched = valid && CreateProcessW(argv[1], command, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &process);
    HeapFree(GetProcessHeap(), 0, command);
    if (!launched) { CloseHandle(ready); if (owner) { status("launch_failed"); ReleaseMutex(mutex); } CloseHandle(mutex); return 6; }
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    if (!owner || !has_account) { CloseHandle(ready); if (owner) ReleaseMutex(mutex); CloseHandle(mutex); return 0; }
    ULONGLONG absent_since = 0;
    for (;;) {
        if (!shared_client_state(FALSE)) status("state_save_failed");
        DWORD size = sizeof(current); BOOL present = read_registry(name, current, &size);
        if (present && (!had_token || size != previous_size || memcmp(previous, current, size))) {
            if (save_session(current, size)) { memcpy(previous, current, size); previous_size = size; had_token = TRUE; status("session_saved"); }
        } else if (!present && had_token && GetLastError() == ERROR_FILE_NOT_FOUND) {
            if (DeleteFileW(AUTH L"session.bin") || GetLastError() == ERROR_FILE_NOT_FOUND) { had_token = FALSE; status("sign_in_required"); }
        }
        if (client_running()) absent_since = 0;
        else if (!absent_since) absent_since = GetTickCount64();
        else if (GetTickCount64() - absent_since >= 15000) break;
        Sleep(1000);
    }
    SecureZeroMemory(previous, sizeof(previous)); SecureZeroMemory(current, sizeof(current));
    CloseHandle(ready); ReleaseMutex(mutex); CloseHandle(mutex); return 0;
}
