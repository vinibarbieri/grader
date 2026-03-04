package com.grader.domain.model;

import com.grader.domain.CompileStatus;

import java.util.List;

public record FileResult(
        String fileName,
        String problem,
        CompileStatus compileStatus,
        String compileDetails,
        List<CaseResult> cases
) {}
