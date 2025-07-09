package com.prporter.report;

import com.prporter.model.ChangedFile;
import com.prporter.model.FileStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportGenerator {
    private static final String REPORT_TEMPLATE = 
            "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>PR Porting Report</title>\n" +
            "    <script src=\"https://cdn.tailwindcss.com\"></script>\n" +
            "</head>\n" +
            "<body class=\"bg-gray-50 text-gray-900 p-8\">\n" +
            "    <h1 class=\"text-3xl font-bold mb-4\">PR Porting Report</h1>\n" +
            "    <p class=\"mb-2\"><span class=\"font-semibold\">Generated on:</span> %s</p>\n" +
            "    <p class=\"mb-6\"><span class=\"font-semibold\">PR Number:</span> %s</p>\n" +
            "    <h2 class=\"text-2xl font-semibold mb-2\">Patched Files Summary</h2>\n" +
            "    %s\n" +
            "    <h2 class=\"text-2xl font-semibold mb-2\">Patched Files Details</h2>\n" +
            "    %s\n" +
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
        String summaryRows = generateSummaryRows(changedFiles);
        String fileRows = generateFileRows(changedFiles);
        String reportContent = String.format(REPORT_TEMPLATE, timestamp, prNumber, summaryRows, fileRows);

        // Create the report file
        String fileName = String.format("pr-porting-report-%s-%s.html", prNumber, 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path reportPath = reportsDir.resolve(fileName);
        Files.write(reportPath, reportContent.getBytes());

        return reportPath.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String generateSummaryRows(List<ChangedFile> changedFiles) {
        StringBuilder rows = new StringBuilder();
        for (ChangedFile file : changedFiles) {
            String statusText;
            String statusClass;
            if (file.getStatus() == FileStatus.PORTED) {
                statusText = "[SUCCESS]";
                statusClass = "bg-green-100 text-green-800 px-2 py-1 rounded font-bold";
            } else if (file.getStatus() == FileStatus.PARTIALLY_PORTED) {
                statusText = "[PARTIAL]";
                statusClass = "bg-yellow-100 text-yellow-800 px-2 py-1 rounded font-bold";
            } else {
                statusText = "[SKIPPED]";
                statusClass = "bg-red-100 text-red-800 px-2 py-1 rounded font-bold";
            }
            rows.append("<div class='mb-2'><span class='" + statusClass + "'>").append(statusText).append("</span> ")
                .append(escapeHtml(file.getPath())).append("</div>\n");
        }
        return rows.toString();
    }

    private String generateDiffDetails(List<ChangedFile.DiffHunk> diffHunks) {
        StringBuilder details = new StringBuilder();
        for (ChangedFile.DiffHunk hunk : diffHunks) {
            details.append(hunk.getContent()).append("\n");
        }
        return details.toString();
    }

    private boolean isUsefulAiSuggestion(String aiSuggestion, String baseFileContent) {
        if (aiSuggestion == null) return false;
        String trimmed = aiSuggestion.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.equalsIgnoreCase("MANUAL REVIEW NEEDED")) return false;
        if (baseFileContent != null && trimmed.equals(baseFileContent.trim())) return false;
        // Hide if suggestion is only a copyright/license block or only comments
        String noComments = trimmed.replaceAll("(?m)^\\s*//.*$", "").replaceAll("(?m)^\\s*/\\*.*?\\*/\\s*$", "").replaceAll("(?m)^\\s*\\*.*$", "").trim();
        if (noComments.isEmpty()) return false;
        return true;
    }

    private String getBaseFileContent(ChangedFile file) {
        try {
            Path basePath = Paths.get(file.getPath());
            if (Files.exists(basePath)) {
                return new String(Files.readAllBytes(basePath));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String generateFileRows(List<ChangedFile> changedFiles) {
        StringBuilder rows = new StringBuilder();
        int aiBlockId = 0;
        for (ChangedFile file : changedFiles) {
            String statusText;
            String statusClass;
            if (file.getStatus() == FileStatus.PORTED) {
                statusText = "[SUCCESS]";
                statusClass = "bg-green-100 text-green-800 px-2 py-1 rounded font-bold";
            } else if (file.getStatus() == FileStatus.PARTIALLY_PORTED) {
                statusText = "[PARTIAL]";
                statusClass = "bg-yellow-100 text-yellow-800 px-2 py-1 rounded font-bold";
            } else {
                statusText = "[SKIPPED]";
                statusClass = "bg-red-100 text-red-800 px-2 py-1 rounded font-bold";
            }
            rows.append("<div class='mb-8 p-4 bg-white rounded shadow'>\n")
                .append("<h3 class='text-lg font-bold mb-2 text-blue-800'>").append(escapeHtml(file.getPath())).append("</h3>\n")
                .append("<div class='mb-2'><span class='" + statusClass + "'>Status: ").append(statusText).append("</span></div>\n");
            if ((file.getStatus() == FileStatus.SKIPPED || file.getStatus() == FileStatus.PARTIALLY_PORTED) && file.getReason() != null) {
                rows.append("<div class='mb-2'><span class='font-semibold text-gray-700'>Reason:</span> ")
                    .append(escapeHtml(file.getReason())).append("</div>\n");
            }
            if (file.getDiffHunks() != null && !file.getDiffHunks().isEmpty()) {
                rows.append("<div class='mb-2'><pre class='bg-gray-900 text-gray-100 rounded p-3 overflow-x-auto text-sm'><code class='language-diff'>")
                    .append(escapeHtml(generateDiffDetails(file.getDiffHunks())))
                    .append("</code></pre></div>\n");
            }
            // Only show AI suggestion if it is useful, and make it collapsible
            String baseFileContent = getBaseFileContent(file);
            if (isUsefulAiSuggestion(file.getAiSuggestion(), baseFileContent) && file.getStatus() != FileStatus.PORTED) {
                String safeSuggestion = escapeHtml(file.getAiSuggestion());
                String blockId = "ai-suggestion-" + (aiBlockId++);
                rows.append("<div class='mb-2 bg-blue-50 border border-blue-200 rounded p-2'>"
                    + "<button type='button' class='font-semibold text-blue-900 focus:outline-none' onclick=\"var e=document.getElementById('" + blockId + "');e.style.display=(e.style.display==='none'?'block':'none');\">AI Suggestion &#x25BC;</button>"
                    + "<div id='" + blockId + "' style='display:none;'><pre class='bg-gray-900 text-gray-100 rounded p-3 overflow-x-auto text-sm mt-2'><code class='language-java'>"
                    + safeSuggestion
                    + "</code></pre></div></div>\n");
            }
            rows.append("</div>\n");
        }
        return rows.toString();
    }
} 