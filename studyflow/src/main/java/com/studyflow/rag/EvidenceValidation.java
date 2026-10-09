package com.studyflow.rag;

import java.util.List;

public record EvidenceValidation(List<GroundedClaim> claims, int rejected, boolean structured) { }
