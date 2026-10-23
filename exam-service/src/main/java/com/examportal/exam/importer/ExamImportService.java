package com.examportal.exam.importer;

import com.examportal.common.domain.dto.ExamImportResponse;
import com.examportal.exam.exception.ExamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Questions from a Word file for the exam creator (POST /api/exams/import/docx). Reads the file (at most
 * MAX_FILE_BYTES, also enforced while the body arrives: CsvUploadLimitFilter), finds the questions and returns them.
 * Nothing is stored and no exam is touched, so it needs no owner check: the questions only become part of an exam
 * when the person saves it through the normal create or update.
 */
@Slf4j
@Service
public class ExamImportService {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    public Mono<ExamImportResponse> importDocx(FilePart file) {
        String name = file.filename() == null ? "" : file.filename().toLowerCase();
        if (!name.endsWith(".docx")) {
            return Mono.error(ExamException.importFileInvalid());
        }
        return DataBufferUtils.join(file.content(), MAX_FILE_BYTES)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                // Unzipping and parsing is CPU work: off the event loop
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    try {
                        return QuestionDocParser.parse(DocxLines.read(bytes));
                    } catch (DocxLines.NotADocxException e) {
                        throw ExamException.importFileInvalid();
                    }
                })
                .doOnNext(result -> log.info("Word import: {} questions, {} warnings", result.getQuestionCount(), result.getWarnings().size()));
    }
}
