
# DartWatch - Agent Instructions

## Project
DartWatch is a personal backend project that monitors corporate
disclosures through OpenDART and sends matching email notifications.

## Tech Stack
- Java 21, Spring Boot
- PostgreSQL, Spring Data JPA
- Spring RestClient, Spring Scheduler
- Spring Mail, Springdoc OpenAPI
- JUnit 5, Mockito
- Docker Compose

## Project Context
- `docs/PROJECT_PLAN.md` is the source of truth for requirements.
- Read the plan when planning features or changing project scope.
- Follow its development phases and MVP priorities.
- Do not read the entire plan for trivial edits.

## Scope
- Prioritize a working single-user MVP.
- Implement only the requested task or development phase.
- Do not add authentication, frontend, AI features, or extra
  infrastructure unless explicitly requested.
- Avoid Redis, Kafka, MSA, and unnecessary dependencies.
- Keep the notification architecture extensible, but implement
  only email notifications in the MVP.

## Architecture and Coding
- Follow existing project conventions and package structure.
- Separate controllers, business logic, persistence, and API clients.
- Keep controllers thin and business logic testable.
- Prefer simple, maintainable code over premature abstraction.
- Use constructor injection.
- Avoid unrelated refactoring.
- Use descriptive names and avoid unnecessary comments.

## Domain Rules
- Distinguish OpenDART corp_code from stock_code.
- Prevent duplicate disclosures using rcept_no.
- Ensure notification processing is idempotent.
- Do not send notifications for historical disclosures
  imported during initial synchronization.
- Make the polling interval configurable (default: 10 minutes).
- Keep notification sending behind a NotificationSender interface.
- Record notification delivery results and failures.

## Testing and Validation
- Add focused tests for new business logic.
- Mock external OpenDART and email services in tests.
- Run relevant tests after code changes.
- Avoid running the full test suite for trivial changes.
- Never claim tests passed unless they were executed.
- Report any tests that could not be executed.

## Security
- Never hardcode API keys, SMTP passwords, or secrets.
- Use environment variables and safe example configuration.
- Do not commit secret files.
- Do not send real emails during testing without approval.

## Workflow
- Inspect relevant files before modifying them.
- For complex tasks, outline a short plan before implementation.
- Keep changes focused on the requested scope.
- Do not create Git commits, push, or deploy without approval.
- At completion, briefly report:
  - Files changed
  - Implementation summary
  - Test results
  - Remaining issues

## Communication
- Respond to the user in Korean.
- Keep explanations concise and practical.
- Preserve English names for code, APIs, and technical terms.
