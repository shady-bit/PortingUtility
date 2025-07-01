# AI Patching Guide

## Overview

The PR Porting Utility includes AI-powered conflict resolution that can automatically apply changes when manual patching fails. This feature uses OpenAI's GPT-3.5-turbo model to understand the intent of changes and apply them intelligently.

## Configuration

### Environment Variables

1. **OPENAI_API_KEY** (required for AI patching)
   - Your OpenAI API key
   - If not set, AI patching will be disabled automatically

2. **DISABLE_AI_PATCHING** (optional)
   - Set to `true` or `1` to disable AI patching
   - Useful when you want to avoid API costs or rate limits
   - Conflicts will be flagged for manual review instead

### Example Setup

```bash
# Enable AI patching
export OPENAI_API_KEY="your-api-key-here"

# Disable AI patching (optional)
export DISABLE_AI_PATCHING="true"
```

## Rate Limiting

The utility now includes improved rate limiting to handle OpenAI API limits:

### Features
- **Exponential Backoff**: Starts with 1 second delay, doubles on each retry
- **Server-Suggested Delays**: Respects OpenAI's `Retry-After` headers
- **Multiple Retry Types**: Handles 429 (rate limit), 5xx (server errors), and network issues
- **Maximum Retries**: 5 attempts before giving up
- **Graceful Degradation**: Falls back to manual review if AI fails

### Retry Delays
- Attempt 1: 1 second
- Attempt 2: 2 seconds  
- Attempt 3: 4 seconds
- Attempt 4: 8 seconds
- Attempt 5: 16 seconds

Or uses OpenAI's suggested delay if provided in `Retry-After` header.

## Usage Scenarios

### When AI Patching is Useful
- Complex refactoring changes
- Method signature changes
- Context-aware modifications
- Large-scale code migrations

### When to Disable AI Patching
- High API costs concerns
- Rate limit issues
- Prefer manual review for all conflicts
- Testing without AI dependencies

## Troubleshooting

### Rate Limit Issues
If you're experiencing frequent rate limits:

1. **Disable AI patching temporarily**:
   ```bash
   export DISABLE_AI_PATCHING="true"
   ```

2. **Check your OpenAI usage**:
   - Visit https://platform.openai.com/usage
   - Consider upgrading your plan if needed

3. **Use manual review**:
   - The utility will flag conflicts for manual review
   - Review the generated report for guidance

### API Key Issues
- Ensure `OPENAI_API_KEY` is set correctly
- Check that your API key has sufficient credits
- Verify the key has access to GPT-3.5-turbo

## Output Messages

The utility provides clear feedback about AI patching:

```
🤖 AI Patching Configuration:
   AI patching is ENABLED
   Rate limiting: Exponential backoff with 5 retries
   To disable: Set DISABLE_AI_PATCHING=true

[AI PATCH] Context does not match for hunk at lines 45-52. Calling AI for help.
[AI PATCH] OpenAI API rate limit exceeded (429). Waiting 2 seconds before retry 1/5...
[AI PATCH] Using server-suggested retry delay: 60 seconds
[AI PATCH] AI could not help. Flagging for manual review.
```

## Best Practices

1. **Start with AI enabled** for complex migrations
2. **Monitor API usage** to control costs
3. **Review AI suggestions** before committing
4. **Use manual review** for critical changes
5. **Set up proper rate limiting** in your OpenAI account

## Cost Considerations

- GPT-3.5-turbo costs approximately $0.002 per 1K tokens
- Typical PR analysis uses 1-5 API calls
- Estimated cost per PR: $0.01-$0.05
- Disable AI patching to avoid costs entirely 