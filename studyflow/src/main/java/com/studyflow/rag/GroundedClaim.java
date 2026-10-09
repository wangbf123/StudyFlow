package com.studyflow.rag;

import java.util.List;

public record GroundedClaim(int index, String text, List<EvidenceSpan> evidence) { }
