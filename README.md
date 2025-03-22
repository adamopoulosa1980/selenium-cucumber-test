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

- **Java**: JDK 11 or higher (tested with JDK 21.0.4)
- **Maven**: 3.6.0 or higher (tested with 3.9.8)
- **Kafka**: A running Kafka cluster (for Kafka-related tests)
- **Chrome**: Chrome browser (tested with version 134, managed via WebDriverManager 5.8.0)
- **Node.js**: Required for the target web app (`selenium-cucumber-webapp`)

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
     baseUrl=http://localhost:3000
     kafka.bootstrap.servers=localhost:9092
     webdriver.chrome.args=--headless,--disable-gpu
     implicitWait=20
     defaultTimeout=30
     retryAttempts=3
     retryDelaySeconds=2
     report.screenshotsOnFailure=true
     ```
   - Set the environment via `-Denv` (default: `dev`).

4. **Run the Web Application**:
   - Navigate to the companion app:
     ```bash
     cd ../selenium-cucumber-webapp
     npm install
     npm start
     ```
   - Ensure it runs on `http://localhost:3000`.

5. **Kafka Setup** (if applicable):
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
  mvn test -Dcucumber.filter.tags="@login_multi_users" -Denv=dev
  ```

- **With Detailed Logging**:
  ```bash
  mvn test -X -e > output.log
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
                           reportName: 'Cucumber Report'
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
| `baseUrl`                 | Base URL of the application             | `http://localhost:3000` |
| `browser`                 | Browser type (e.g., `chrome`)           | `chrome`          |
| `implicitWait`            | Implicit wait in seconds                | `20`              |
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
page.login.elements.username.locator[0].type=id
page.login.elements.username.locator[0].value=username
page.login.elements.password.locator[0].type=id
page.login.elements.password.locator[0].value=password
page.login.elements.loginButton.locator[0].type=id
page.login.elements.loginButton.locator[0].value=loginButton
page.cart.path=/cart
```

### Test Scenarios (`tests.properties`)

```properties
test.login_multi_users.description=Login with multiple users
test.login_multi_users.dataFile=testdata/users.csv
test.login_multi_users.resetDriverPerIteration=true
test.login_multi_users.startPage=login
test.login_multi_users.actions[0].action=navigate
test.login_multi_users.actions[0].targetPage=login
test.login_multi_users.actions[1].page=login
test.login_multi_users.actions[1].element=username
test.login_multi_users.actions[1].action=enter
test.login_multi_users.actions[1].value=${data.username}
test.login_multi_users.actions[2].page=login
test.login_multi_users.actions[2].element=password
test.login_multi_users.actions[2].action=enter
test.login_multi_users.actions[2].value=${data.password}
test.login_multi_users.actions[3].page=login
test.login_multi_users.actions[3].element=loginButton
test.login_multi_users.actions[3].action=click
test.login_multi_users.actions[4].action=navigate
test.login_multi_users.actions[4].targetPage=cart
test.login_multi_users.assertions[0].type=url
test.login_multi_users.assertions[0].value=/cart
test.login_multi_users.assertions[0].condition=contains
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
| `navigate`       | Navigates to a URL                       | `targetPage`                                |
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

## Example Test: Login with Multiple Users

```properties
test.login_multi_users.description=Login with multiple users
test.login_multi_users.dataFile=testdata/users.csv
test.login_multi_users.resetDriverPerIteration=true
test.login_multi_users.startPage=login
test.login_multi_users.actions[0].action=navigate
test.login_multi_users.actions[0].targetPage=login
test.login_multi_users.actions[1].page=login
test.login_multi_users.actions[1].element=username
test.login_multi_users.actions[1].action=enter
test.login_multi_users.actions[1].value=${data.username}
test.login_multi_users.actions[2].page=login
test.login_multi_users.actions[2].element=password
test.login_multi_users.actions[2].action=enter
test.login_multi_users.actions[2].value=${data.password}
test.login_multi_users.actions[3].page=login
test.login_multi_users.actions[3].element=loginButton
test.login_multi_users.actions[3].action=click
test.login_multi_users.actions[4].action=navigate
test.login_multi_users.actions[4].targetPage=cart
test.login_multi_users.assertions[0].type=url
test.login_multi_users.assertions[0].value=/cart
test.login_multi_users.assertions[0].condition=contains
```

## Extending the Suite

1. **Add a New Page**: Update `pages.properties`.
2. **Add a New Test**: Define in `tests.properties`.
3. **Add Test Data**: Use CSV/JSON files.

## Troubleshooting

- **Kafka Errors**: Verify broker and topics.
- **File Uploads**: Ensure files exist at specified paths.
- **CI/CD Issues**: Check ChromeDriver setup and network access.
- **Navigation Timeouts**:
  - Verify `http://localhost:3000` is responsive.
  - Test without `--headless`:
    ```bash
    mvn test -Dwebdriver.chrome.args=--disable-gpu
    ```
  - Enable ChromeDriver logs:
    ```java
    System.setProperty("webdriver.chrome.logfile", "chromedriver.log");
    ```

## Contributing

Submit pull requests or issues to enhance the suite!

## Updates

- **March 18, 2025**:
  - Updated `login_multi_users` assertion to use `condition=contains` for robust URL checking.
  - Enhanced `GenericSteps.java` with navigation timeout retries and detailed logging.

## License
MIT License.