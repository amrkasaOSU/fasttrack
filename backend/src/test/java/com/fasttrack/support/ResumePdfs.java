package com.fasttrack.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds real PDFs for tests, so the upload path exercises PDFBox rather than
 * a stubbed extractor.
 */
public final class ResumePdfs {

    private ResumePdfs() {
    }

    public static byte[] of(String... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.setLeading(14);
                content.newLineAtOffset(50, 750);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A realistic backend-leaning resume used by several tests. */
    public static byte[] backendResume() throws IOException {
        return of(
                "Amr Khasawneh",
                "The Ohio State University, Bachelor of Science in Computer Science",
                "Expected graduation May 2027",
                "Skills: Java, Python, SQL, Spring Boot, React, PostgreSQL, Docker, Git, AWS",
                "Software Engineering Intern, Regional Insurance, Summer 2026",
                "Built REST endpoints in Spring Boot backed by PostgreSQL for the claims portal",
                "Containerised a batch job with Docker and deployed it through CI",
                "Projects: FastTrack internship tracker using Spring Boot, PostgreSQL and Next.js");
    }
}
