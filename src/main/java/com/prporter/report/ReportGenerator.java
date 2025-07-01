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
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>PR Porting Report</title>\n" +
            "    <script src=\"https://cdn.tailwindcss.com\"></script>\n" +
            "    <script>\n" +
            "        document.addEventListener('DOMContentLoaded', function() {\n" +
            "            var coll = document.getElementsByClassName('collapsible');\n" +
            "            for (var i = 0; i < coll.length; i++) {\n" +
            "                coll[i].addEventListener('click', function() {\n" +
            "                    this.classList.toggle('active');\n" +
            "                    var content = this.nextElementSibling;\n" +
            "                    if (content.style.display === 'block') {\n" +
            "                        content.style.display = 'none';\n" +
            "                    } else {\n" +
            "                        content.style.display = 'block';\n" +
            "                    }\n" +
            "                });\n" +
            "            }\n" +
            "        });\n" +
            "    </script>\n" +
            "</head>\n" +
            "<body class=\"bg-gray-100\">\n" +
            "    <div class=\"max-w-5xl mx-auto my-10 bg-white rounded-xl shadow-lg p-8\">\n" +
            "        <div class=\"bg-gradient-to-r from-purple-700 to-blue-600 text-white rounded-lg p-8 mb-8 shadow\">\n" +
            "            <h1 class=\"text-3xl font-bold mb-2\">PR Porting Report</h1>\n" +
            "            <p class=\"mb-1\">Generated on: %s</p>\n" +
            "            <p>PR Number: %s</p>\n" +
            "        </div>\n" +
            "        <h2 class=\"text-xl font-semibold text-gray-800 border-b border-gray-200 pb-2 mb-4\">Patched Files Summary</h2>\n" +
            "        <table class=\"min-w-full mb-8 rounded-lg overflow-hidden\">\n" +
            "            <thead class=\"bg-gray-50\">\n" +
            "                <tr>\n" +
            "                    <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">File</th>\n" +
            "                    <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Status</th>\n" +
            "                </tr>\n" +
            "            </thead>\n" +
            "            <tbody class=\"bg-white divide-y divide-gray-200\">\n" +
            "                %s\n" +
            "            </tbody>\n" +
            "        </table>\n" +
            "        <h2 class=\"text-xl font-semibold text-gray-800 border-b border-gray-200 pb-2 mb-4\">Patched Files Details</h2>\n" +
            "        <table class=\"min-w-full rounded-lg overflow-hidden\">\n" +
            "            <thead class=\"bg-gray-50\">\n" +
            "                <tr>\n" +
            "                    <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">File</th>\n" +
            "                    <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Status</th>\n" +
            "                    <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Details</th>\n" +
            "                </tr>\n" +
            "            </thead>\n" +
            "            <tbody class=\"bg-white divide-y divide-gray-200\">\n" +
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

    private String generateSummaryRows(List<ChangedFile> changedFiles) {
        StringBuilder rows = new StringBuilder();
        for (ChangedFile file : changedFiles) {
            String statusText;
            if (file.getStatus() == FileStatus.PORTED) {
                statusText = "<span class='inline-block bg-green-100 text-green-800 text-xs font-semibold px-3 py-1 rounded-full'>Successfully Ported</span>";
            } else if (file.getStatus() == FileStatus.PARTIALLY_PORTED) {
                statusText = "<span class='inline-block bg-yellow-100 text-yellow-800 text-xs font-semibold px-3 py-1 rounded-full'>Partially Ported</span>";
            } else {
                statusText = "<span class='inline-block bg-red-100 text-red-800 text-xs font-semibold px-3 py-1 rounded-full'>Skipped</span>";
            }
            rows.append("<tr>\n")
                .append("    <td class='px-6 py-4 whitespace-nowrap'>").append(Jsoup.clean(file.getPath(), Safelist.basic())).append("</td>\n")
                .append("    <td class='px-6 py-4 whitespace-nowrap'>").append(statusText).append("</td>\n")
                .append("</tr>\n");
        }
        return rows.toString();
    }

    private String generateFileRows(List<ChangedFile> changedFiles) {
        StringBuilder rows = new StringBuilder();
        for (ChangedFile file : changedFiles) {
            String statusClass;
            String statusIcon;
            String statusText;
            if (file.getStatus() == FileStatus.PORTED) {
                statusClass = "text-green-700 font-semibold";
                statusIcon = "✅";
                statusText = "Successfully Ported";
            } else if (file.getStatus() == FileStatus.PARTIALLY_PORTED) {
                statusClass = "text-yellow-700 font-semibold";
                statusIcon = "⚠️";
                statusText = "Partially Ported";
            } else {
                statusClass = "text-red-700 font-semibold";
                statusIcon = "❌";
                statusText = "Skipped";
            }
            String details = "";
            if ((file.getStatus() == FileStatus.SKIPPED || file.getStatus() == FileStatus.PARTIALLY_PORTED) && file.getReason() != null) {
                String highlight = file.getReason().toLowerCase().contains("manual review mandatory") ? "bg-yellow-50 border-l-4 border-yellow-400 text-yellow-800 p-4 rounded mb-2" : "";
                details = "<div class='" + highlight + "'>" +
                          "<strong>" + statusIcon + " " + statusText + ": </strong>" + Jsoup.clean(file.getReason(), Safelist.basic()) + "</div>";
            } else if (file.getDiffHunks() != null && !file.getDiffHunks().isEmpty()) {
                details = generateDiffDetails(file.getDiffHunks());
            }
            if (file.getAiSuggestion() != null && !file.getAiSuggestion().trim().isEmpty() && file.getStatus() != FileStatus.PORTED) {
                String safeSuggestion = Jsoup.clean(file.getAiSuggestion(), Safelist.basic());
                details += "<button type='button' class='collapsible bg-blue-100 text-blue-800 font-semibold rounded px-4 py-2 mt-2 mb-1'>Show AI Suggestion</button>" +
                           "<div class='content'><pre class='bg-gray-900 text-white rounded p-4'>" + safeSuggestion + "</pre></div>";
            }
            rows.append("<tr>\n")
                .append("    <td class='px-6 py-4 whitespace-nowrap'>").append(Jsoup.clean(file.getPath(), Safelist.basic())).append("</td>\n")
                .append("    <td class='px-6 py-4 whitespace-nowrap ").append(statusClass).append("'>")
                .append(statusIcon).append(" ").append(statusText).append("</td>\n")
                .append("    <td class='px-6 py-4'>").append(details).append("</td>\n")
                .append("</tr>\n");
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
                    details.append("<div class='diff-added'>")
                           .append(Jsoup.clean(line, Safelist.basic()))
                           .append("</div>");
                } else if (line.startsWith("-")) {
                    details.append("<div class='diff-removed'>")
                           .append(Jsoup.clean(line, Safelist.basic()))
                           .append("</div>");
                } else {
                    details.append("<div>")
                           .append(Jsoup.clean(line, Safelist.basic()))
                           .append("</div>");
                }
            }
        }
        
        details.append("</div>");
        return details.toString();
    }
} 