# Step 5 — SafeZipExtractor

## What was done

- Added `org.apache.commons:commons-compress:1.26.2` to `pom.xml`.
- Wrote `SafeZipExtractorTest.java` (8 tests) — tests first.
- Implemented `SafeZipExtractor.java`.

## Why Apache Commons Compress

Java's standard `java.util.zip.ZipEntry` does not expose Unix external file
attributes, so there is no portable way to detect symlinks or non-standard file
types via the JDK alone.  Apache Commons Compress's `ZipArchiveEntry` exposes
`getUnixMode()` / `setUnixMode()`, making symlink detection straightforward and
reliable.  `UnixStat` provides the standard flag constants (`FILE_FLAG`,
`LINK_FLAG`, `DIR_FLAG`, `FILE_TYPE_FLAG`).

## Validation rules implemented

| Rule | Check | Rejection message |
|------|-------|-------------------|
| Absolute path | name starts with `/` or `\` | `Rejected absolute path entry` |
| Path traversal | name contains `..` | `Rejected path traversal entry (contains '..')` |
| Symlink | Unix file type == `S_IFLNK` (0xA000) | `Rejected symlink entry` |
| Non-standard type | type not in {0, S_IFREG, S_IFDIR} | `Rejected entry with non-standard Unix file type` |
| Canonical escape | resolved path does not start with `targetDir` | `Rejected entry that escapes the target directory` |

The `..` check is intentionally broad (substring match).  Filenames containing
`..` internally (e.g. `file..name`) are rejected.  This is an acceptable
trade-off in the security-sensitive grading context where filenames are
well-controlled.  The canonical-escape check provides defense-in-depth against
any edge cases not caught by the prior string checks.

## Tests written

| Test | Scenario |
|------|----------|
| `validZip_extractsFilesUnderTargetDir` | Two regular files in nested paths extracted correctly |
| `validZip_directoryEntry_isCreated` | Directory-only entry creates directory |
| `emptyZip_extractsWithoutError` | Empty ZIP completes without exception |
| `absolutePath_throwsZipSecurityException` | Entry `/etc/passwd` rejected |
| `pathTraversalWithDotDot_throwsZipSecurityException` | Entry `../../etc/passwd` rejected |
| `pathTraversalEmbedded_throwsZipSecurityException` | Entry `submissions/../../etc/passwd` rejected |
| `symlinkEntry_throwsZipSecurityException` | Entry with `S_IFLNK` Unix mode rejected |
| `nonStandardUnixFileType_throwsZipSecurityException` | Block-device entry (0x6000) rejected |

## Test outcome

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
Total across all suites: 53 tests, 0 failures, BUILD SUCCESS
```
