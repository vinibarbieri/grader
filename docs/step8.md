# Step 8 — CSV Aggregation + Results Model

## What was done

Implemented `CsvAggregationService` and its full test suite for the CSV-to-`StudentResult` aggregation pipeline.

### Files created

| File | Role |
|---|---|
| `src/test/java/com/grader/service/CsvAggregationServiceTest.java` | 19 tests — written first (TDD) |
| `src/main/java/com/grader/service/CsvAggregationService.java` | Implementation |

The domain records (`StudentResult`, `FileResult`, `CaseResult`) were already in place from Step 2.

---

## CSV format

The parser handles the exact format produced by `avaliacao_automatica.sh`:

```
student_dir,c_file,problem,case,status,details,expected_output,program_output
```

- All fields RFC 4180 double-quoted (`""` to escape internal double-quotes).
- Multi-line output encoded as literal `\n` sequences (not actual newlines), as produced by:
  ```bash
  awk '{printf "%s\\n", $0}' "$file"
  ```
- Decoded back to actual newlines via `unescapeOutput()` during parsing.
- A `-` value in output fields is treated as empty string.

---

## Naming convention

`{sanitizedName}__{submissionId}` e.g. `Alice__submission_12345`

- `studentName` = part before `__`
- `studentId` = numeric suffix after last `_` in the submission part (e.g. `12345`)
- Falls back to full directory name if the pattern is not matched.

Source: `renomear_submissoes.sh` uses `${base_name}__${submission_id}` where `submission_id` comes from the Gradescope metadata YAML keys like `submission_12345`.

---

## Key design decisions

### CSV parser (no external dependency)

A minimal RFC 4180 parser was written inline — no OpenCSV or similar library was needed. The parser handles:
- Quoted fields with `""` internal escaping.
- Unquoted fields.
- Line-by-line splitting (valid because the shell script encodes embedded newlines as `\n` literals, so actual newlines only appear at row boundaries).

### Compile error detection

A row is treated as a file-level compile error when `status=COMPILE_ERROR` AND `case="-"`. In this case the `FileResult` gets `compileStatus=COMPILE_ERROR`, `compileDetails` from the `details` field, and an **empty cases list**.

### Scoring formula

```
problem1Score = (p1Ok / p1Total) * maxScorePerProblem   [default max = 20]
problem2Score = (p2Ok / p2Total) * maxScorePerProblem
totalScore    = problem1Score + problem2Score
```

If `p1Total == 0` (no executable cases — e.g. compile error only), `problem1Score = 0`.

Cases counted toward totals: `OK`, `WA`, `RUNTIME_ERROR`, `TIMEOUT`, `OUTPUT_LIMIT_EXCEEDED`.
Cases excluded from totals: `SKIP`, `INTERNAL_ERROR`, `COMPILE_ERROR` (in case position).

### `failedAnyQuestion` flag

```java
failedAnyQuestion = compileErrorsCount > 0 || runtimeErrorsCount > 0
                  || timeoutsCount > 0 || waCount > 0
```

### JSON persistence

`saveResultsJson(List<StudentResult>, Path)` uses Jackson with `INDENT_OUTPUT` to write a pretty-printed `results.json` at the given path.

---

## Test coverage

| Test | Scenario |
|---|---|
| `emptyBody_returnsEmptyList` | Header-only CSV |
| `headerOnly_returnsEmptyList` | Bare header string |
| `singleStudentSingleOkCase_parsedCorrectly` | Full field mapping, name/ID parsing |
| `mixedStatuses_countersAggregatedCorrectly` | OK, WA, TIMEOUT, RUNTIME_ERROR, OLE |
| `allOk_failedAnyQuestionIsFalse` | `failedAnyQuestion` flag |
| `skipStatus_notCountedInOkOrError` | SKIP case row |
| `compileError_fileHasNoTestCases` | Compile error row → empty cases |
| `compileError_doesNotCountTowardProblemTotal` | Score = 0 for errored problem |
| `scoring_perfectScore_bothProblems` | 100% OK on both problems |
| `scoring_partialOk_scoreIsProportional` | 2/4 OK → 10.0 score |
| `scoring_noTestCasesForProblem_scoreIsZero` | No cases → 0 score |
| `multipleStudents_eachAggregatedSeparately` | Two students, independent counts |
| `outputWithLiteralBackslashN_convertedToActualNewline` | `\n` literal → actual newline |
| `dashOutputField_convertedToEmptyString` | `-` → `""` |
| `studentNameParsed_fromDoubleUnderscorePattern` | `John_Doe__submission_54321` → name/id |
| `studentNameParsed_whenNoDoubleUnderscore_usesFullDirAsName` | Fallback |
| `quotedFieldWithEscapedDoubleQuote_parsedCorrectly` | RFC 4180 `""` escaping |
| `multipleFilesPerStudent_allAggregated` | Two files, two problems, per-problem scoring |
| `saveResultsJson_writesReadableJsonFile` | JSON written and contains expected keys |

---

## Test outcomes

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
Total build: Tests run: 98, Failures: 0, Errors: 0, Skipped: 24
BUILD SUCCESS
```

(24 skipped = integration tests that require a real OS environment, unchanged from prior steps.)
