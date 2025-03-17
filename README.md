# Selenium-Cucumber Automation Suite

This is a highly configurable, reliable, and extensible automation testing suite built with Selenium, Cucumber, and Java. It is designed to test web applications, including those interacting with external systems via Kafka events and REST APIs, while supporting complex user journeys, file uploads, and dynamic test scenarios. The suite is CI/CD-friendly and integrates seamlessly with Jenkins pipelines.

## Features
- **100% Configurable**: Define pages, elements, tests, and external interactions in properties files—no code changes required.
- **Reliable**: Uses Awaitility for robust waits and a retry mechanism to handle transient failures.
- **User Journey Support**: Simulates navigation through multiple pages via configurable actions.
- **External System Integration**: Supports Kafka message production/consumption and REST API calls.
- **File Uploads**: Upload JSON, XML, PDF, or other files to test application features.
- **Data-Driven Testing**: Loads test data from CSV/JSON files.
- **Conditional Logic**: Executes actions based on conditions (e.g., element visibility).
- **Parallel Execution**: Runs tests concurrently for faster execution.
- **Dynamic Locators**: Handles elements with parameterized locators.
- **Logging & Reporting**: Detailed logs and failure screenshots integrated with Cucumber reports.
- **Environment Switching**: Supports multiple environments (e.g., dev, prod) via configuration.
- **Custom Waits**: Allows JavaScript-based wait conditions.
- **State Persistence**: Saves and restores browser state (e.g., cookies) across steps.
- **CI/CD Ready**: Headless mode, WebDriverManager, and JUnit reporting for Jenkins integration.

## Prerequisites
- **Java**: JDK 11 or higher
- **Maven**: 3.6.0 or higher
- **Kafka**: A running Kafka cluster (for Kafka-related tests)
- **Chrome**: Chrome browser (managed via WebDriverManager in CI/CD)

## Setup
1. **Clone the Repository**:
   ```bash
   git clone <repository-url>
   cd selenium-cucumber-test
   ```

2. **Install Dependencies**:
   ```bash
   mvn clean install
   ```

3. **Configure Environment**:
   - Copy `src/test/resources/config.dev.properties` to `config.<env>.properties` (e.g., `config.prod.properties`) and update settings:
     ```properties
     baseUrl=https://dev.example.com
     kafka.bootstrap.servers=localhost:9092
     ```
   - Set the environment via `-Denv` (default: `dev`).

4. **Kafka Setup** (if applicable):
   - Ensure a Kafka broker is running at `kafka.bootstrap.servers`.
   - Create necessary topics (e.g., `order-created`, `order-processed`).

## Project Structure
```
selenium-cucumber-test/
├── src/
│   ├── test/
│   │   ├── java/
│   │   │   ├── steps/         # Step definitions (GenericSteps.java)
│   │   │   ├── utils/         # Config management (ConfigManager.java)
│   │   │   └── runner/        # Test runner (TestRunner.java)
│   │   └── resources/
│   │       ├── config.dev.properties  # Environment-specific config
│   │       ├── pages/         # Page definitions
│   │       │   └── pages.properties
│   │       ├── testdata/      # Test data and scenarios
│   │       │   ├── tests.properties
│   │       │   └── users.csv
│   │       └── features/      # Cucumber feature files
│   │           └── GenericTests.feature
├── pom.xml                    # Maven configuration
├── Jenkinsfile                # Jenkins pipeline definition
└── README.md                  # This file
```

## Running Tests
- **Default (dev environment)**:
  ```bash
  mvn test
  ```
- **Specific Environment**:
  ```bash
  mvn test -Denv=prod
  ```
- **Single Test**:
  ```bash
  mvn test -Dcucumber.filter.tags="@json_upload" -Denv=dev
  ```

Reports are generated in `target/cucumber-reports.html`.

## CI/CD Integration (Jenkins)
1. **Pipeline Setup**:
   - Use the provided `Jenkinsfile` for a basic pipeline.
   - Configure Jenkins with Maven and JDK tools.

2. **Run in Headless Mode**:
   ```bash
   mvn test -Dwebdriver.chrome.args=--headless,--disable-gpu
   ```

3. **Sample Jenkinsfile**:
```groovy
pipeline {
    agent any
    tools {
        maven 'Maven'
        jdk 'JDK11'
    }
    stages {
        stage('Checkout') {
            steps {
                git url: '<repository-url>', branch: 'main'
            }
        }
        stage('Build') {
            steps {
                sh 'mvn clean install -DskipTests'
            }
        }
        stage('Run Tests') {
            steps {
                sh 'mvn test -Denv=dev -Dbrowser=chrome -Dcucumber.options="--tags @smoke" -Dwebdriver.chrome.args=--headless,--disable-gpu'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'target/cucumber-reports.html', allowEmptyArchive: true
                    publishHTML(target: [
                        reportDir: 'target',
                        reportFiles: 'cucumber-reports.html',
                        reportName | 'Cucumber Report'
                    ])
                    junit 'target/surefire-reports/*.xml'
                }
            }
        }
    }
}
```

## Configuration

### General Config (`config.<env>.properties`)
| Property                  | Description                              | Default            |
|---------------------------|------------------------------------------|--------------------|
| `baseUrl`                 | Base URL of the application             | `https://dev.example.com` |
| `browser`                 | Browser type (e.g., `chrome`)           | `chrome`          |
| `implicitWait`            | Implicit wait in seconds                | `10`              |
| `defaultTimeout`          | Default wait timeout in seconds         | `30`              |
| `retryAttempts`           | Number of retry attempts                | `3`               |
| `retryDelaySeconds`       | Delay between retries in seconds        | `2`               |
| `logLevel`                | Logging level (e.g., `INFO`, `DEBUG`)   | `INFO`            |
| `report.screenshotsOnFailure` | Capture screenshots on failure      | `true`            |
| `report.outputDir`        | Directory for reports                   | `target/test-reports` |
| `kafka.bootstrap.servers` | Kafka broker addresses                  | `localhost:9092`  |
| `kafka.group.id`          | Kafka consumer group ID                 | `test-group`      |
| `rest.timeout.seconds`    | REST call timeout in seconds            | `10`              |
| `webdriver.chrome.args`   | ChromeDriver arguments (e.g., headless) | `--headless,--disable-gpu` |

### Page Definitions (`pages/pages.properties`)
```properties
page.login.path=/login
page.login.elements.username.type=input
page.login.elements.username.locator[0].type=id
page.login.elements.username.locator[0].value=username
```

### Test Scenarios (`tests.properties`)
```properties
test.json_upload.actions[0].page=upload
test.json_upload.actions[0].element=jsonUpload
test.json_upload.actions[0].action=uploadFile
test.json_upload.actions[0].value=${param.jsonFile.path}
```

### Test Data (`users.csv`)
```csv
username,password
user1@example.com,Pass123
user2@example.com,Pass456
```

## Supported Actions
| Action           | Description                              | Properties                                  |
|------------------|------------------------------------------|---------------------------------------------|
| `enter`          | Enters text into an input                | `page`, `element`, `value`                  |
| `click`          | Clicks an element                        | `page`, `element`                           |
| `navigate`       | Navigates to another page via click      | `page`, `element`, `targetPage`             |
| `kafkaProduce`   | Sends a Kafka message                    | `kafka.topic`, `kafka.key`, `kafka.value`   |
| `kafkaConsume`   | Consumes a Kafka message                 | `kafka.topic`, `kafka.key`, `kafka.valueContains` |
| `restCall`       | Makes a REST API call                    | `rest.method`, `rest.url`, `rest.body`, `rest.header.*` |
| `check`          | Conditional action                       | `condition`, `ifTrue.nextAction`, `ifFalse.nextAction` |
| `saveState`      | Saves browser state                      | `stateKey`                                  |
| `loadState`      | Loads saved state                        | `stateKey`                                  |
| `uploadFile`     | Uploads a file to an input element       | `page`, `element`, `value` (file path)      |

## Supported Assertions
| Type         | Description                              | Properties              |
|--------------|------------------------------------------|-------------------------|
| `url`        | Checks current URL                       | `value`, `condition`    |
| `visible`    | Checks element visibility                | `page`, `element`, `condition` |
| `text`       | Checks element text                      | `page`, `element`, `value`, `condition` |

## Example Test: JSON Upload
```properties
test.json_upload.description=Upload JSON file to start a process
test.json_upload.actions[0].page=upload
test.json_upload.actions[0].element=jsonUpload
test.json_upload.actions[0].action=uploadFile
test.json_upload.actions[0].value=${param.jsonFile.path}
test.json_upload.actions[1].page=upload
test.json_upload.actions[1].element=submitButton
test.json_upload.actions[1].action=click
test.json_upload.assertions[0].page=upload
test.json_upload.assertions[0].element=successMessage
test.json_upload.assertions[0].type=visible
test.json_upload.assertions[0].condition=true
```

## Extending the Suite
1. **Add a New Page**: Update `pages.properties`.
2. **Add a New Test**: Define in `tests.properties`.
3. **Add Test Data**: Use CSV/JSON files.

## Troubleshooting
- **Kafka Errors**: Verify broker and topics.
- **File Uploads**: Ensure files exist at specified paths.
- **CI/CD Issues**: Check ChromeDriver setup and network access.

## Contributing
Submit pull requests or issues to enhance the suite!