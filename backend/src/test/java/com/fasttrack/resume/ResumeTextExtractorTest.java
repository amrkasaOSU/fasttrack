package com.fasttrack.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasttrack.common.BadRequestException;
import com.fasttrack.support.ResumePdfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PDF handling, including the uploads that should be refused. */
class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    @DisplayName("text is extracted from a real PDF")
    void extractsText() throws IOException {
        byte[] pdf = ResumePdfs.of(
                "Amr Khasawneh - Software Engineer",
                "The Ohio State University, BS Computer Science, expected graduation May 2027",
                "Skills: Java, Spring Boot, PostgreSQL, Docker, React, TypeScript, AWS",
                "Experience: Software Engineering Intern at Regional Insurance, Summer 2026",
                "Built REST endpoints in Spring Boot backed by PostgreSQL and added JUnit coverage",
                "Projects: FastTrack, an internship tracker built with Spring Boot and Next.js");

        String text = extractor.extract(pdf);

        assertThat(text).contains("Spring Boot").contains("May 2027").contains("PostgreSQL");
    }

    @Test
    @DisplayName("a file that is not a PDF is refused before parsing")
    void refusesNonPdf() {
        assertThatThrownBy(() -> extractor.extract("hello".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not a PDF");
    }

    @Test
    @DisplayName("an empty upload is refused")
    void refusesEmpty() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("an oversized file is refused with the limit stated")
    void refusesOversized() {
        byte[] huge = new byte[(int) ResumeTextExtractor.MAX_BYTES + 1];
        huge[0] = '%';
        huge[1] = 'P';
        huge[2] = 'D';
        huge[3] = 'F';

        assertThatThrownBy(() -> extractor.extract(huge))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2 MB");
    }

    @Test
    @DisplayName("a PDF with no text layer is refused with a usable explanation")
    void refusesImageOnlyPdf() throws IOException {
        byte[] almostEmpty = ResumePdfs.of("x");

        assertThatThrownBy(() -> extractor.extract(almostEmpty))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scan");
    }

    @Test
    @DisplayName("a damaged PDF is reported, not thrown as a server error")
    void refusesDamagedPdf() {
        byte[] damaged = "%PDF-1.7\nthis is not actually a pdf body".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(damaged))
                .isInstanceOf(BadRequestException.class);
    }
}
