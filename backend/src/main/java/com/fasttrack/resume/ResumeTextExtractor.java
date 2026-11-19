package com.fasttrack.resume;

import com.fasttrack.common.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Pulls the text out of an uploaded resume PDF.
 *
 * <p>Validation happens here rather than at the controller because the only
 * reliable test of "is this a PDF" is trying to open it: an extension and a
 * content type are both client-supplied.
 */
@Component
public class ResumeTextExtractor {

    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** Below this, the upload is almost certainly a scan with no text layer. */
    private static final int MIN_USABLE_CHARACTERS = 200;

    public String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new BadRequestException("That file is empty.");
        }
        if (pdfBytes.length > MAX_BYTES) {
            throw new BadRequestException("Resumes are limited to 2 MB.");
        }
        if (!looksLikePdf(pdfBytes)) {
            throw new BadRequestException("That file is not a PDF.");
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new BadRequestException(
                        "That PDF is password protected. Save an unprotected copy and try again.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String cleaned = clean(text);

            if (cleaned.length() < MIN_USABLE_CHARACTERS) {
                throw new BadRequestException(
                        "No text could be read from that PDF. If it is a scan or an image, "
                        + "export a text-based PDF from your editor and try again.");
            }
            return cleaned;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BadRequestException("That PDF could not be read. It may be damaged.");
        }
    }

    public String extract(InputStream input) {
        try {
            return extract(input.readAllBytes());
        } catch (IOException ex) {
            throw new BadRequestException("The upload could not be read.");
        }
    }

    private boolean looksLikePdf(byte[] bytes) {
        return bytes.length > 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    /** Collapses the ragged whitespace PDF extraction produces. */
    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(' ', ' ')
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
