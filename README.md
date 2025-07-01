# PR Porting Utility

This utility automates the process of porting pull request changes between branches, using AI to adapt code changes even when the code structure has evolved.

## Features
- **AI-powered patching:** Every diff hunk is applied using OpenAI's GPT, which adapts the change to the current file structure and intent.
- **Manual review workflow:** If the AI cannot safely apply a change, the file is flagged for manual review, and the AI's suggestion is included in the report.
- **Syntax checking:** After patching, each file is compiled. If compilation fails, the file is flagged for manual review and the compiler output is included in the report.
- **Beautiful HTML reports:** Reports are generated with Tailwind CSS, featuring a summary table, detailed status, collapsible AI suggestions, and clear visual cues inspired by GitHub PR pages.
- **Configurable timeouts:** All OpenAI API timeouts are configurable via a properties file.

## Prerequisites
- Java 11 or higher
- Maven or Gradle (for building/running the project)
- An OpenAI API key (set as the `OPENAI_API_KEY` environment variable)

## Configuration

### 1. OpenAI API Key
Set your OpenAI API key as an environment variable:
```sh
export OPENAI_API_KEY=sk-...
```

### 2. AI Patcher Properties
Edit `ai-patcher.properties` in the project root to control OpenAI API timeouts:
```properties
openai.connectTimeoutSeconds=30
openai.writeTimeoutSeconds=30
openai.readTimeoutSeconds=120
```

## How to Run
1. Build the project with Maven or Gradle.
2. Run the main class (e.g., `com.prporter.Main`) as you would a standard Java application.
3. The utility will process PRs, apply patches using AI, and generate a report in the `reports/` directory.

## Report Features
- **Summary Table:** Quick overview of all files and their patch status.
- **Details Table:** For each file, see status, patch details, and (if needed) a collapsible AI suggestion.
- **Status Badges:**
  - ✅ **Successfully Ported**: All hunks applied and file compiles.
  - ⚠️ **Partially Ported**: Some hunks failed or file did not compile; manual review required.
  - ❌ **Skipped**: No hunks could be applied.
- **Compiler Output:** If a file fails to compile, the error output is included in the report.
- **Modern UI:** Styled with Tailwind CSS, inspired by GitHub PR pages.

## Customizing AI Timeouts
Edit `ai-patcher.properties` and restart the utility to change timeouts for OpenAI API calls.

## Roadmap
- **Spring Boot migration:** The project will soon be refactored as a Spring Boot application for easier extensibility and web/API support.

---

For questions or contributions, please open an issue or pull request!