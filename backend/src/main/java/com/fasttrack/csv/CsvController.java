package com.fasttrack.csv;

import com.fasttrack.auth.AuthorizationService;
import com.fasttrack.common.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** CSV import and export for the applications table. */
@RestController
@RequestMapping("/api/applications")
@Tag(name = "Applications", description = "CSV import and export")
public class CsvController {

    /** 5 MB is far more than a realistic tracker export and bounds the parse. */
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final CsvApplicationService csv;
    private final AuthorizationService authorization;

    public CsvController(CsvApplicationService csv, AuthorizationService authorization) {
        this.csv = csv;
        this.authorization = authorization;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a tracker spreadsheet (Company, Position, Date Applied, Status, Location, Notes)")
    public CsvApplicationService.ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a CSV file to import.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("That file is larger than the 5 MB import limit.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".csv")) {
            throw new BadRequestException("Only .csv files can be imported.");
        }
        try {
            return csv.importCsv(authorization.requireUserId(), file.getInputStream());
        } catch (IOException e) {
            throw new BadRequestException("The upload could not be read.");
        }
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Export every application as CSV")
    public ResponseEntity<byte[]> exportCsv() {
        String body = csv.exportCsv(authorization.requireUserId());
        String filename = "fasttrack-applications-" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
