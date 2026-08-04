package com.studyflow.ocr;

import java.util.List;

public interface OcrClient {

    OcrResult recognize(String fileName, String fileType, byte[] bytes, List<Integer> pageNumbers);
}
