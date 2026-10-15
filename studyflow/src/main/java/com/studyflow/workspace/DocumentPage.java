package com.studyflow.workspace;

import com.studyflow.document.DocumentResponse;
import java.util.List;

public record DocumentPage(List<DocumentResponse> items, Long nextCursor, boolean hasMore) {}
