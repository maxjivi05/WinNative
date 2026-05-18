#include "wn_steam/depot_writer.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#include "wn_steam/depot_chunk.h"

namespace wn_steam {

namespace {
constexpr const char* kLogTag = "WnSteamDepotWriter";
#define WN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)
#define WN_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  kLogTag, __VA_ARGS__)

// EDepotFileFlag bits we care about.
constexpr uint32_t kFlagExecutable = 32;
constexpr uint32_t kFlagDirectory  = 64;

// Steam's chunk checksum (ContentManifest ChunkData.crc) — an Adler-32
// variant seeded with a=0 (NOT zlib's a=1). Matches steam_adler_hash in
// depot_chunk.cpp / SteamKit2 Util.AdlerHash. Used by the resume path to
// check whether a chunk's bytes are already correct on disk.
uint32_t depot_adler_hash(std::span<const uint8_t> data) {
    uint32_t a = 0, b = 0;
    for (uint8_t byte : data) {
        a = (a + byte) % 65521;
        b = (b + a)    % 65521;
    }
    return a | (b << 16);
}

DepotWriteResult fail(std::string msg) {
    DepotWriteResult r;
    r.error = std::move(msg);
    WN_LOGE("%s", r.error.c_str());
    return r;
}

// A manifest filename is server-controlled. Reject any path that could
// escape target_dir (absolute, or a ".." component) before touching disk.
bool path_is_safe(std::string_view rel) {
    if (rel.empty()) return false;
    if (rel.front() == '/') return false;
    size_t start = 0;
    while (start <= rel.size()) {
        size_t slash = rel.find('/', start);
        std::string_view comp = rel.substr(
            start, slash == std::string_view::npos ? std::string_view::npos
                                                   : slash - start);
        if (comp == "..") return false;
        if (slash == std::string_view::npos) break;
        start = slash + 1;
    }
    return true;
}

// mkdir -p for the directory portion of `path` (everything before the last
// '/'). Existing directories are fine.
bool make_parent_dirs(const std::string& path) {
    size_t slash = path.rfind('/');
    if (slash == std::string::npos || slash == 0) return true;
    std::string dir = path.substr(0, slash);
    std::string acc;
    acc.reserve(dir.size());
    for (size_t i = 0; i <= dir.size(); ++i) {
        if (i == dir.size() || dir[i] == '/') {
            if (!acc.empty() && acc != "/") {
                if (::mkdir(acc.c_str(), 0755) != 0 && errno != EEXIST) {
                    WN_LOGE("mkdir(%s): %s", acc.c_str(), std::strerror(errno));
                    return false;
                }
            }
        }
        if (i < dir.size()) acc.push_back(dir[i]);
    }
    return true;
}

}  // namespace

DepotWriteResult write_depot(const ContentManifest& manifest,
                             std::span<const uint8_t> depot_key,
                             CdnClient& cdn,
                             const pb::CContentServerDirectory_ServerInfo& server,
                             const std::string& target_dir,
                             std::string_view cdn_auth_token,
                             const DepotWriteProgress& progress,
                             const std::atomic<bool>* cancel) {
    if (manifest.metadata.filenames_encrypted) {
        return fail("write_depot: manifest filenames are still encrypted");
    }
    if (depot_key.size() != 32) return fail("write_depot: bad depot key length");

    const auto cancelled = [cancel]() {
        return cancel && cancel->load();
    };

    uint64_t total_bytes = 0;
    for (const auto& f : manifest.files) total_bytes += f.size;

    DepotWriteResult result;
    for (const auto& f : manifest.files) {
        if (cancelled()) return fail("write_depot: cancelled");
        if (!path_is_safe(f.filename)) {
            return fail("write_depot: unsafe path '" + f.filename + "'");
        }
        const std::string path = target_dir + "/" + f.filename;

        // Symlink — identified by a non-empty linktarget.
        if (!f.linktarget.empty()) {
            if (!make_parent_dirs(path)) return fail("write_depot: mkdir failed");
            ::unlink(path.c_str());
            if (::symlink(f.linktarget.c_str(), path.c_str()) != 0) {
                return fail("write_depot: symlink '" + f.filename + "': "
                            + std::strerror(errno));
            }
            ++result.files_written;
            continue;
        }

        // Directory. The manifest file list is not guaranteed parent-first,
        // so create any missing ancestors before the directory itself.
        if (f.flags & kFlagDirectory) {
            if (!make_parent_dirs(path)) return fail("write_depot: mkdir failed");
            if (::mkdir(path.c_str(), 0755) != 0 && errno != EEXIST) {
                return fail("write_depot: mkdir '" + f.filename + "': "
                            + std::strerror(errno));
            }
            continue;
        }

        // Regular file.
        if (!make_parent_dirs(path)) return fail("write_depot: mkdir failed");
        const mode_t mode = (f.flags & kFlagExecutable) ? 0755 : 0644;
        // Resume: a pre-existing file with content may already hold some of
        // this depot's chunks (a paused / interrupted download). When so,
        // each chunk is verified on disk below and skipped if already
        // correct — so do NOT O_TRUNC, and open O_RDWR to read it back.
        // ftruncate still fixes the file to the exact manifest size.
        struct stat prev_st {};
        const bool had_content =
            (::stat(path.c_str(), &prev_st) == 0 && prev_st.st_size > 0);
        int fd = ::open(path.c_str(), O_RDWR | O_CREAT, mode);
        if (fd < 0) {
            return fail("write_depot: open '" + f.filename + "': "
                        + std::strerror(errno));
        }
        if (f.size > 0 && ::ftruncate(fd, static_cast<off_t>(f.size)) != 0) {
            ::close(fd);
            return fail("write_depot: ftruncate '" + f.filename + "'");
        }

        for (const auto& chunk : f.chunks) {
            if (cancelled()) {
                ::close(fd);
                return fail("write_depot: cancelled");
            }

            // Resume fast-path: skip the CDN fetch + decode when the chunk's
            // exact decompressed bytes are already on disk (verified by
            // Steam's Adler32). This is what makes pause/resume continue
            // where it left off instead of re-downloading the depot.
            if (had_content && chunk.cb_original > 0) {
                std::vector<uint8_t> on_disk(chunk.cb_original);
                ssize_t rd = ::pread(fd, on_disk.data(), on_disk.size(),
                                     static_cast<off_t>(chunk.offset));
                if (rd == static_cast<ssize_t>(on_disk.size()) &&
                    depot_adler_hash(on_disk) == chunk.crc) {
                    result.bytes_written += chunk.cb_original;
                    if (progress) progress(result.bytes_written, total_bytes);
                    continue;
                }
            }

            auto fetched = cdn.fetch_chunk(server, manifest.metadata.depot_id,
                                           chunk.sha, cdn_auth_token);
            if (!fetched.ok()) {
                ::close(fd);
                return fail("write_depot: chunk fetch failed for '" + f.filename
                            + "': " + fetched.error);
            }
            auto processed = process_depot_chunk(fetched.data, depot_key,
                                                 chunk.crc, chunk.cb_original);
            if (!processed.ok()) {
                ::close(fd);
                return fail("write_depot: chunk decode failed for '" + f.filename
                            + "': " + processed.error);
            }
            ssize_t w = ::pwrite(fd, processed.data.data(), processed.data.size(),
                                 static_cast<off_t>(chunk.offset));
            if (w < 0 || static_cast<size_t>(w) != processed.data.size()) {
                ::close(fd);
                return fail("write_depot: pwrite '" + f.filename + "': "
                            + std::strerror(errno));
            }
            result.bytes_written += processed.data.size();
            if (progress) progress(result.bytes_written, total_bytes);
        }
        ::close(fd);
        ++result.files_written;
    }

    WN_LOGI("write_depot: depot %u — %llu files, %llu bytes",
            manifest.metadata.depot_id,
            static_cast<unsigned long long>(result.files_written),
            static_cast<unsigned long long>(result.bytes_written));
    return result;
}

}  // namespace wn_steam
