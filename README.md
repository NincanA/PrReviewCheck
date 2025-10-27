# GitHub PR Review Bot

An intelligent GitHub PR review bot built with Spring Boot that automatically analyzes pull requests and provides intelligent feedback using LLM APIs (OpenAI, Anthropic, or Google Gemini).

## Features

### Core Functionality
- **Automatic PR Analysis**: Monitors pull requests and analyzes code changes
- **Line-Specific Comments**: Posts review comments on specific lines where issues are found
- **Multi-LLM Support**: Works with OpenAI GPT, Anthropic Claude, or Google Gemini
- **GitHub App Integration**: Secure authentication using GitHub Apps
- **Webhook Processing**: Real-time event handling for PR events

### Advanced Features (Bonus)
- **Conversational AI**: Responds to user questions about review comments
- **Context-Aware Responses**: Understands the context of original suggestions
- **Smart Prompting**: Optimized prompts for better code analysis
- **Configurable Review Settings**: Customizable review parameters

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   GitHub PR     │───▶│  Webhook        │───▶│  PR Review      │
│   Events        │    │  Controller     │    │  Service        │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                                         │
                                                         ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Review         │◀───│  GitHub         │◀───│  LLM Service    │
│  Comments       │    │  Service        │    │  (OpenAI/       │
└─────────────────┘    └─────────────────┘    │  Anthropic/     │
                                              │  Google)        │
                                              └─────────────────┘
```

## Prerequisites

- Java 17 or higher
- Gradle 7.0 or higher
- GitHub App (for authentication)
- LLM API key (OpenAI, Anthropic, or Google)

## Setup Instructions

### 1. GitHub App Setup

1. **Create a GitHub App**:
   - Go to GitHub Settings → Developer settings → GitHub Apps
   - Click "New GitHub App"
   - Fill in the required information:
     - App name: `pr-review-bot`
     - Homepage URL: Your app URL
     - Webhook URL: `https://your-domain.com/webhook`
     - Webhook secret: Generate a random secret

2. **Configure Permissions**:
   - Repository permissions:
     - Pull requests: Read & Write
     - Contents: Read
     - Metadata: Read
   - Subscribe to events:
     - Pull request
     - Pull request review comment

3. **Generate Private Key**:
   - Download the private key file
   - Convert it to a single-line string for environment variables

4. **Install the App**:
   - Install the app on your repositories
   - Note the App ID and Installation ID

### 2. Environment Configuration

1. **Copy Environment File**:
   ```bash
   cp .env.example .env
   ```

2. **Configure Environment Variables**:
   ```bash
   # GitHub App Configuration
   GITHUB_APP_ID=123456
   GITHUB_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----\nYour private key here\n-----END RSA PRIVATE KEY-----"
   GITHUB_WEBHOOK_SECRET=your_webhook_secret
   GITHUB_BOT_NAME=pr-review-bot

   # LLM Configuration (choose one)
   LLM_PROVIDER=openai
   OPENAI_API_KEY=your_openai_api_key
   ```

### 3. Local Development Setup

1. **Install Dependencies**:
   ```bash
   ./gradlew build
   ```

2. **Run the Application**:
   ```bash
   ./gradlew bootRun
   ```

3. **Expose Local Server** (for webhook testing):
   ```bash
   # Using ngrok
   ngrok http 8080
   
   # Update webhook URL in GitHub App settings
   # https://your-ngrok-url.ngrok.io/webhook
   ```

### 4. Production Deployment

1. **Build the Application**:
   ```bash
   ./gradlew clean build
   ```

2. **Run with Environment Variables**:
   ```bash
   java -jar build/libs/github-pr-review-bot-1.0.0.jar
   ```

3. **Docker Deployment** (optional):
   ```dockerfile
   FROM openjdk:17-jdk-slim
   COPY build/libs/github-pr-review-bot-1.0.0.jar app.jar
   EXPOSE 8080
   ENTRYPOINT ["java", "-jar", "/app.jar"]
   ```

## Configuration

### Application Properties

The application uses `application.yml` for configuration:

```yaml
github:
  app:
    id: ${GITHUB_APP_ID}
    private-key: ${GITHUB_PRIVATE_KEY}
    webhook-secret: ${GITHUB_WEBHOOK_SECRET}
  bot:
    name: ${GITHUB_BOT_NAME:pr-review-bot}

llm:
  provider: ${LLM_PROVIDER:openai}
  openai:
    api-key: ${OPENAI_API_KEY}
    model: ${OPENAI_MODEL:gpt-4}
    max-tokens: ${OPENAI_MAX_TOKENS:4000}

review:
  max-files-per-pr: ${MAX_FILES_PER_PR:50}
  max-lines-per-file: ${MAX_LINES_PER_FILE:1000}
  enable-conversational-features: ${ENABLE_CONVERSATIONAL_FEATURES:true}
```

### LLM Provider Configuration

#### OpenAI
```bash
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-your-api-key
OPENAI_MODEL=gpt-4
OPENAI_MAX_TOKENS=4000
```

#### Anthropic
```bash
LLM_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-your-api-key
ANTHROPIC_MODEL=claude-3-sonnet-20240229
ANTHROPIC_MAX_TOKENS=4000
```

#### Google Gemini
```bash
LLM_PROVIDER=google
GOOGLE_API_KEY=your-api-key
GOOGLE_MODEL=gemini-pro
GOOGLE_MAX_TOKENS=4000
```

## Usage

### Automatic PR Review

The bot automatically reviews pull requests when:
- A new PR is opened
- New commits are pushed to an existing PR

### Manual Interaction

Users can interact with the bot by replying to its comments:

```
@pr-review-bot why is this a security issue?
@pr-review-bot can you explain this bug?
@pr-review-bot how should I fix this?
```

### Review Categories

The bot categorizes issues into:
- 🐛 **BUG**: Logic errors and potential bugs
- 🔒 **SECURITY**: Security vulnerabilities
- ⚡ **PERFORMANCE**: Performance bottlenecks
- 🎨 **STYLE**: Code style and formatting
- 🔧 **MAINTAINABILITY**: Code maintainability issues

## API Endpoints

### Health Check
```
GET /webhook/health
```

### Webhook Endpoint
```
POST /webhook
```

## Development

### Project Structure

```
src/
├── main/
│   ├── java/com/github/prreviewbot/
│   │   ├── config/          # Configuration classes
│   │   ├── controller/       # REST controllers
│   │   ├── service/         # Business logic
│   │   └── GitHubPrReviewBotApplication.java
│   └── resources/
│       └── application.yml  # Application configuration
└── test/                    # Test files
```

### Running Tests

```bash
./gradlew test
```

### Code Style

The project follows standard Java conventions and Spring Boot best practices.

## Troubleshooting

### Common Issues

1. **Webhook Signature Validation Failed**:
   - Check that `GITHUB_WEBHOOK_SECRET` matches the webhook secret in GitHub App settings
   - Ensure the webhook URL is accessible

2. **GitHub API Authentication Failed**:
   - Verify `GITHUB_APP_ID` and `GITHUB_PRIVATE_KEY` are correct
   - Ensure the app is installed on the repository

3. **LLM API Errors**:
   - Check API key validity
   - Verify API quotas and rate limits
   - Check network connectivity

### Logging

The application uses SLF4J with Logback. Logs are written to:
- Console (development)
- `logs/combined.log` (all logs)
- `logs/error.log` (error logs only)

### Monitoring

Health check endpoint: `GET /webhook/health`

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License.

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review the logs
3. Create an issue in the repository

## Roadmap

- [ ] Support for more LLM providers
- [ ] Custom review rules configuration
- [ ] Integration with CI/CD pipelines
- [ ] Advanced code analysis features
- [ ] Multi-language support
- [ ] Review comment templates
