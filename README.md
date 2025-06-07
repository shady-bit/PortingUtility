# PR Porting Utility

A Java Maven application that automates porting changes from a specific pull request (PR) to a target branch in a Git repository. The utility analyzes the PR changes, checks for conflicts, and applies only the exact changes introduced in the PR.

## Features

- Clones the Git repository and checks out both source and target branches
- Creates a new port branch from the target branch for changes
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

The utility supports multiple authentication methods:

#### HTTPS Authentication (Recommended)
For HTTPS repositories, you need to set up a personal access token:

1. Create a GitHub personal access token:
   - Go to GitHub Settings > Developer Settings > Personal Access Tokens
   - Generate a new token with `repo` scope
   - Copy the token

2. Set the token as an environment variable:
   ```bash
   # For Linux/Mac
   export GITHUB_TOKEN=your_token_here

   # For Windows PowerShell
   $env:GITHUB_TOKEN="your_token_here"
   ```

3. Run the utility:
   ```bash
   java -jar target/pr-porting-utility-1.0-SNAPSHOT-jar-with-dependencies.jar https://github.com/username/repo.git feature-branch main 123
   ```

#### SSH Authentication
1. Ensure you have an SSH key pair generated:
   ```bash
   ssh-keygen -t ed25519 -C "your_email@example.com"
   ```
2. Add the public key to your Git hosting service (GitHub, GitLab, etc.)
3. The utility will automatically use your default SSH key from `~/.ssh/`

Note: For security reasons, always use a personal access token with minimal required permissions.

### Branching Strategy

The utility implements a safe porting strategy:

1. Creates a new branch named `{targetBranch}-port-{prNumber}` (e.g., "main-port-123")
2. Applies all changes to this new branch
3. Creates commits in the new branch

After running the utility:
1. Push the new port branch to the remote repository:
   ```bash
   git push origin {targetBranch}-port-{prNumber}
   ```
2. Create a pull request from the port branch to the target branch
3. Review and merge the changes

This approach allows for:
- Safe review of changes before merging to target branch
- Easy rollback if issues are found
- Clean separation of ported changes

## Output

The utility generates an HTML report in the `reports` directory with the following information:

- List of files that were processed
- Status of each file (Successfully Ported or Skipped)
- Reason for skipping if applicable
- Diff information for successfully ported files
- Name of the created port branch

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