package com.studyflow.qa;

import java.util.List;

public record QaClaimResponse(Long claimId, String text, String verificationStatus, List<QaCitationResponse> evidence) { }
