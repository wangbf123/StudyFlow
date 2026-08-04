package com.studyflow.upload;

public final class UploadSessionStatus {

    public static final String INIT = "INIT";
    public static final String UPLOADING = "UPLOADING";
    public static final String MERGING = "MERGING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private UploadSessionStatus() {
    }
}
