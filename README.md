# PR Porting Utility

A Java Maven application that automates porting changes from a specific pull request (PR) to a target branch in a Git repository. The utility analyzes the PR changes, checks for conflicts, and applies only the exact changes introduced in the PR.

## Features

- Clones the Git repository and checks out both source and target branches
- Analyzes pull request changes at the file and line level
- Detects potential conflicts before applying changes
- Applies changes only if no conflicts are found
- Generates an HTML report with detailed porting status and diff information

## Requirements

- Java 8 or higher
- Maven 3.6 or higher
- Git repository with pull request access

## Building the Project

```bash
mvn clean package
```

This will create a runnable JAR file in the `target` directory.

## Usage

```bash
java -jar target/pr-porting-utility-1.0-SNAPSHOT-jar-with-dependencies.jar <repoUrl> <sourceBranch> <targetBranch> <prNumber>
```

### Parameters

- `repoUrl`: URL of the Git repository (supports both HTTPS and SSH URLs)
- `sourceBranch`: Branch where the PR was raised
- `targetBranch`: Branch where changes need to be ported
- `prNumber`: Pull request number

### Example

```bash
# Using HTTPS URL
java -jar target/pr-porting-utility-1.0-SNAPSHOT-jar-with-dependencies.jar https://github.com/username/repo.git feature-branch main 123

# Using SSH URL
java -jar target/pr-porting-utility-1.0-SNAPSHOT-jar-with-dependencies.jar git@github.com:username/repo.git feature-branch main 123
```

### Authentication

The utility uses your system's Git credentials for authentication. Make sure you have:
- For HTTPS: Git credentials configured in your credential manager
- For SSH: SSH keys set up and added to your Git hosting service

## Output

The utility generates an HTML report in the `reports` directory with the following information:

- List of files that were processed
- Status of each file (Successfully Ported or Skipped)
- Reason for skipping if applicable
- Diff information for successfully ported files

## Project Structure

```
src/main/java/com/prporter/
├── Main.java                 # Application entry point
├── analyzer/
│   └── PRAnalyzer.java       # Analyzes PR changes
├── checker/
│   └── ConflictChecker.java  # Checks for conflicts
├── model/
│   ├── ChangedFile.java      # Represents a changed file
│   └── FileStatus.java       # File status enum
├── patcher/
│   └── FilePatcher.java      # Applies changes to target branch
└── report/
    └── ReportGenerator.java  # Generates HTML report
```

## Error Handling

The utility handles various scenarios:

- Invalid repository URL or branch names
- Git authentication issues
- File conflicts
- File access permissions
- Invalid PR number

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.