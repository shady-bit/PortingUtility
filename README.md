# PR Porting Utility

A safe and efficient utility for porting changes from one branch to another. This tool helps you analyze, check for conflicts, and port changes from a merged PR to another branch.

## 🔒 Safety First

This utility is designed with safety as the top priority:
- **Read-Only by Default**: All operations are read-only until you explicitly push changes
- **No Remote Modifications**: Never modifies or deletes remote branches
- **Local Changes Only**: All changes are made locally until you choose to push them
- **Explicit Control**: You have full control over when and how changes are pushed

## 🚀 Features

- Analyzes changes from a merged PR
- Checks for potential conflicts before porting
- Generates detailed reports of changes
- Creates local branches for porting
- Provides clear logging of all operations
- Safe repository state verification

## 📋 Prerequisites

- Java 8 or higher
- Git installed and configured
- GitHub Personal Access Token (for HTTPS repositories)
- SSH key configured (for SSH repositories)

## 🔧 Setup

1. Clone the repository:
```bash
git clone <repository-url>
```

2. Build the project:
```bash
./gradlew build
```

3. Set up your GitHub token (for HTTPS repositories):
```bash
export GITHUB_TOKEN=your_token_here
```

## 🎯 Usage

Run the utility with the following parameters:
```bash
java -jar pr-porting-utility.jar <repoUrl> <sourceBranch> <targetBranch> <prNumber>
```

Example:
```bash
java -jar pr-porting-utility.jar https://github.com/username/repo.git main develop 123
```

### Parameters:
- `repoUrl`: URL of the Git repository
- `sourceBranch`: Branch containing the merged PR
- `targetBranch`: Branch where changes should be ported
- `prNumber`: Number of the PR to port

## 🔍 How It Works

1. **Analysis Phase**:
   - Fetches latest repository information
   - Analyzes the merged PR
   - Identifies changed files
   - Checks for potential conflicts

2. **Porting Phase**:
   - Creates a local branch for porting
   - Applies changes locally
   - Generates a report of ported changes

3. **Review Phase**:
   - Review the changes locally
   - Test the changes
   - Push changes when ready

## ⚠️ Important Notes

- The utility never modifies remote branches
- All changes are made locally until you explicitly push them
- You have full control over when to push changes
- Always review changes before pushing

## 🔐 Security

- No remote branches are modified or deleted
- Credentials are handled securely
- All operations are logged for transparency
- Repository state is verified before operations

## 🛠️ Troubleshooting

If you encounter issues:

1. Check your credentials:
   - For HTTPS: Verify your GitHub token
   - For SSH: Verify your SSH key configuration

2. Verify repository access:
   - Ensure you have read access to the repository
   - Check if the PR exists and has been merged

3. Check branch names:
   - Verify source and target branch names
   - Ensure branches exist in the repository

## 📝 License

[Your License Here]

## 🤝 Contributing

Contributions are welcome! Please read our contributing guidelines before submitting pull requests.