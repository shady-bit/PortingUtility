package com.prporter.report;

import com.prporter.model.ChangedFile;
import com.prporter.model.FileStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportGenerator {
    private static final String REPORT_TEMPLATE = 
            "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>PR Porting Report</title>\n" +
            "    <style>\n" +
            "        body { font-family: Arial, sans-serif; margin: 20px; }\n" +
            "        .container { max-width: 1200px; margin: 0 auto; }\n" +
            "        .header { background-color: #f5f5f5; padding: 20px; border-radius: 5px; margin-bottom: 20px; }\n" +
            "        .file-list { border-collapse: collapse; width: 100%; }\n" +
            "        .file-list th, .file-list td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }\n" +
            "        .file-list th { background-color: #f5f5f5; }\n" +
            "        .status-success { color: #28a745; }\n" +
            "        .status-skipped { color: #dc3545; }\n" +
            "        .diff { background-color: #f8f9fa; padding: 10px; border-radius: 5px; font-family: monospace; }\n" +
            "        .diff-added { color: #28a745; }\n" +
            "        .diff-removed { color: #dc3545; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <div class=\"container\">\n" +
            "        <div class=\"header\">\n" +
            "            <h1>PR Porting Report</h1>\n" +
            "            <p>Generated on: %s</p>\n" +
            "            <p>PR Number: %s</p>\n" +
            "        </div>\n" +
            "        <table class=\"file-list\">\n" +
            "            <thead>\n" +
            "                <tr>\n" +
            "                    <th>File</th>\n" +
            "                    <th>Status</th>\n" +
            "                    <th>Details</th>\n" +
            "                </tr>\n" +
            "            </thead>\n" +
            "            <tbody>\n" +
            "                %s\n" +
            "            </tbody>\n" +
            "        </table>\n" +
            "    </div>\n" +
            "</body>\n" +
            "</html>";

    public String generateReport(List<ChangedFile> changedFiles, String prNumber) throws IOException {
        // Create reports directory if it doesn't exist
        Path reportsDir = Paths.get("reports");
        if (!Files.exists(reportsDir)) {
            Files.createDirectories(reportsDir);
        }

        // Generate the report content
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String fileRows = generateFileRows(changedFiles);
        String reportContent = String.format(REPORT_TEMPLATE, timestamp, prNumber, fileRows);

        // Create the report file
        String fileName = String.format("pr-porting-report-%s-%s.html", prNumber, 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path reportPath = reportsDir.resolve(fileName);
        Files.write(reportPath, reportContent.getBytes());

        return reportPath.toString();
    }

    private String generateFileRows(List<ChangedFile> changedFiles) {
        StringBuilder rows = new StringBuilder();
        
        for (ChangedFile file : changedFiles) {
            String statusClass = file.getStatus() == FileStatus.PORTED ? "status-success" : "status-skipped";
            String statusIcon = file.getStatus() == FileStatus.PORTED ? "✅" : "❌";
            String statusText = file.getStatus() == FileStatus.PORTED ? "Successfully Ported" : "Skipped";
            
            String details = "";
            if (file.getStatus() == FileStatus.SKIPPED && file.getReason() != null) {
                details = String.format("<div class='diff'>%s</div>", 
                    Jsoup.clean(file.getReason(), Safelist.basic()));
            } else if (file.getDiffHunks() != null && !file.getDiffHunks().isEmpty()) {
                details = generateDiffDetails(file.getDiffHunks());
            }
            
            rows.append(String.format(
                "<tr>\n" +
                "    <td>%s</td>\n" +
                "    <td class=\"%s\">%s %s</td>\n" +
                "    <td>%s</td>\n" +
                "</tr>\n",
                Jsoup.clean(file.getPath(), Safelist.basic()),
                statusClass,
                statusIcon,
                statusText,
                details
            ));
        }
        
        return rows.toString();
    }

    private String generateDiffDetails(List<ChangedFile.DiffHunk> diffHunks) {
        StringBuilder details = new StringBuilder();
        details.append("<div class='diff'>");
        
        for (ChangedFile.DiffHunk hunk : diffHunks) {
            String[] lines = hunk.getContent().split("\n");
            for (String line : lines) {
                if (line.startsWith("+")) {
                    details.append(String.format("<div class='diff-added'>%s</div>",
                        Jsoup.clean(line, Safelist.basic())));
                } else if (line.startsWith("-")) {
                    details.append(String.format("<div class='diff-removed'>%s</div>",
                        Jsoup.clean(line, Safelist.basic())));
                } else {
                    details.append(String.format("<div>%s</div>",
                        Jsoup.clean(line, Safelist.basic())));
                }
            }
        }
        
        details.append("</div>");
        return details.toString();
    }
} 