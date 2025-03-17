package steps;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.UnreachableBrowserException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.bonigarcia.wdm.WebDriverManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import utils.ConfigManager;

public class GenericSteps {

    private static final Logger logger = LoggerFactory.getLogger(GenericSteps.class);
    private WebDriver driver;
    private Map<String, Map<String, WebElement>> pages = new HashMap<>();
    private Map<String, Set<org.openqa.selenium.Cookie>> savedStates = new HashMap<>();
    private int retryAttempts;
    private int retryDelaySeconds;
    private Scenario scenario;
    private KafkaProducer<String, String> kafkaProducer;
    private KafkaConsumer<String, String> kafkaConsumer;
    private OkHttpClient httpClient;
    private boolean kafkaEnabled;

    @Before
    public void setUp(Scenario scenario) {
        this.scenario = scenario;
        logger.info("Starting setup for scenario: {}", scenario.getName());
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        String chromeArgs = ConfigManager.getConfig("webdriver.chrome.args");
        logger.debug("Chrome arguments: {}", chromeArgs);
        if (chromeArgs != null && !chromeArgs.isEmpty()) {
            options.addArguments(chromeArgs.split(","));
        }
        initializeDriver(options);
        retryAttempts = Integer.parseInt(ConfigManager.getConfig("retryAttempts"));
        retryDelaySeconds = Integer.parseInt(ConfigManager.getConfig("retryDelaySeconds"));

        kafkaEnabled = Boolean.parseBoolean(ConfigManager.getConfig("kafka.enabled"));
        logger.debug("Kafka enabled: {}", kafkaEnabled);
        if (kafkaEnabled) {
            Properties producerProps = new Properties();
            producerProps.put("bootstrap.servers", ConfigManager.getConfig("kafka.bootstrap.servers"));
            producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            kafkaProducer = new KafkaProducer<>(producerProps);

            Properties consumerProps = new Properties();
            consumerProps.put("bootstrap.servers", ConfigManager.getConfig("kafka.bootstrap.servers"));
            consumerProps.put("group.id", ConfigManager.getConfig("kafka.group.id"));
            consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            kafkaConsumer = new KafkaConsumer<>(consumerProps);
        } else {
            logger.info("Kafka is disabled; skipping Kafka initialization");
        }

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Integer.parseInt(ConfigManager.getConfig("rest.timeout.seconds"))))
                .readTimeout(Duration.ofSeconds(Integer.parseInt(ConfigManager.getConfig("rest.timeout.seconds"))))
                .build();

        logger.info("Setup completed for scenario: {}", scenario.getName());
    }

    @After
    public void tearDown() {
        logger.info("Tearing down scenario: {}", scenario.getName());
        if (driver != null) {
            if (scenario.isFailed() && Boolean.parseBoolean(ConfigManager.getConfig("report.screenshotsOnFailure"))) {
                try {
                    File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    byte[] screenshotBytes = Files.readAllBytes(screenshot.toPath());
                    scenario.attach(screenshotBytes, "image/png", "failure-screenshot");
                    logger.error("Scenario failed, screenshot attached");
                } catch (IOException e) {
                    logger.error("Failed to capture screenshot", e);
                }
            }
            try {
                driver.quit();
            } catch (Exception e) {
                logger.warn("Failed to quit driver during teardown", e);
            }
            driver = null; // Ensure driver is null after quitting
        }
        if (kafkaEnabled) {
            if (kafkaProducer != null) {
                kafkaProducer.close();
            }
            if (kafkaConsumer != null) {
                kafkaConsumer.close();
            }
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    private void initializeDriver(ChromeOptions options) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            logger.debug("Initializing ChromeDriver with options: {} (Attempt {}/{})", options.getCapability("goog:chromeOptions"), attempt, maxAttempts);
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    logger.warn("Failed to quit existing driver cleanly on attempt {}/{}", attempt, maxAttempts, e);
                }
            }
            try {
                driver = new ChromeDriver(options);
                driver.manage().timeouts().implicitlyWait(
                        Long.parseLong(ConfigManager.getConfig("implicitWait")),
                        TimeUnit.SECONDS
                );
                pages.clear();
                logger.debug("ChromeDriver initialized successfully on attempt {}/{}", attempt, maxAttempts);
                return; // Success, exit the loop
            } catch (Exception e) {
                logger.error("Failed to initialize ChromeDriver on attempt {}/{}", attempt, maxAttempts, e);
                if (attempt == maxAttempts) {
                    throw new RuntimeException("ChromeDriver initialization failed after " + maxAttempts + " attempts", e);
                }
                try {
                    Thread.sleep(2000); // Wait 2 seconds before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting to retry driver initialization", ie);
                }
            }
        }
    }

    @Given("user is on the {string} page")
    public void userIsOnPage(String pageId) {
        String url = ConfigManager.getConfig("baseUrl") + ConfigManager.getPageProperty(pageId, "path");
        String expectedPath = ConfigManager.getPageProperty(pageId, "path");
        logger.info("Navigating to page: {} at URL: {}, expecting path: {}", pageId, url, expectedPath);
        executeWithRetry("Navigate to page '" + pageId + "' at URL: " + url, () -> {
            if (driver == null) {
                throw new IllegalStateException("Driver is null before navigation");
            }
            logger.debug("Loading URL: {}", url);
            driver.get(url);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> {
                        String currentUrl = driver.getCurrentUrl();
                        logger.debug("Current URL: {}, expecting path: {}", currentUrl, expectedPath);
                        return currentUrl.contains(expectedPath);
                    });
            loadPageElements(pageId, null);
        });
    }

    @When("user executes test {string}")
    public void userExecutesTest(String testId) {
        logger.info("Starting test execution: {}", testId);
        List<Map<String, String>> testData = ConfigManager.getTestData(testId);
        List<Map<String, String>> actions = ConfigManager.getTestActions(testId);

        if (testData == null) {
            logger.debug("No test data found, executing actions directly");
            executeActions(actions, null);
        } else {
            String resetDriverProp = ConfigManager.getConfig(testId + ".resetDriverPerIteration");
            boolean resetDriverPerIteration = resetDriverProp != null ? Boolean.parseBoolean(resetDriverProp) : false;
            String startPage = ConfigManager.getConfig(testId + ".startPage");
            ChromeOptions options = new ChromeOptions();
            String chromeArgs = ConfigManager.getConfig("webdriver.chrome.args");
            if (chromeArgs != null && !chromeArgs.isEmpty()) {
                options.addArguments(chromeArgs.split(","));
            }

            for (Map<String, String> dataRow : testData) {
                logger.info("Executing test {} with data: {}", testId, dataRow);
                if (resetDriverPerIteration) {
                    initializeDriver(options);
                    if (driver == null) {
                        throw new IllegalStateException("Driver is null after reinitialization for test " + testId + " with data: " + dataRow);
                    }
                    if (startPage != null) {
                        userIsOnPage(startPage);
                    }
                }
                executeActions(actions, dataRow);
            }
        }
    }

    private void executeActions(List<Map<String, String>> actions, Map<String, String> data) {
        AtomicInteger actionIndex = new AtomicInteger(0);
        while (actionIndex.get() < actions.size()) {
            Map<String, String> action = actions.get(actionIndex.get());
            String pageId = action.get("page");
            String elementId = action.get("element");
            String actionType = action.get("action");
            String value = ConfigManager.resolveParameters(action.get("value"), data);
            String targetPage = action.get("targetPage");

            String actionDescription = String.format("Action '%s' on page '%s'%s with value '%s'%s",
                    actionType, pageId != null ? pageId : "N/A",
                    elementId != null ? ", element '" + elementId + "'" : "",
                    value != null ? value : "N/A",
                    targetPage != null ? ", target page '" + targetPage + "'" : "");

            logger.debug("Processing action index {}: {}", actionIndex.get(), action);
            executeWithRetry(actionDescription, () -> {
                logger.debug("Executing action: {}", action);
                applyWait(action, pageId, elementId, data);
                if (elementId != null && pageId != null) {
                    loadPageElements(pageId, action);
                }
                WebElement element = elementId != null && pageId != null ? pages.get(pageId).get(elementId) : null;

                switch (actionType) {
                    case "enter":
                        logger.debug("Attempting to enter value '{}' into element '{}'", value, elementId);
                        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        element.clear();
                        element.sendKeys(value);
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> {
                                    String currentValue = element.getAttribute("value");
                                    logger.debug("Current value in element '{}': '{}'", elementId, currentValue);
                                    return currentValue.equals(value);
                                });
                        break;
                    case "click":
                        logger.debug("Attempting to click element '{}'", elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        element.click();
                        break;
                    case "select":
                        logger.debug("Selecting value '{}' in element '{}'", value, elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        Select select = new Select(element);
                        select.selectByVisibleText(value);
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> select.getFirstSelectedOption().getText().equals(value));
                        break;
                    case "hover":
                        logger.debug("Hovering over element '{}'", elementId);
                        new org.openqa.selenium.interactions.Actions(driver)
                                .moveToElement(element)
                                .perform();
                        break;
                    case "clear":
                        logger.debug("Clearing element '{}'", elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        element.clear();
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> element.getAttribute("value").isEmpty());
                        break;
                    case "submit":
                        logger.debug("Submitting form with element '{}'", elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        element.submit();
                        break;
                    case "doubleClick":
                        logger.debug("Double-clicking element '{}'", elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        new org.openqa.selenium.interactions.Actions(driver)
                                .doubleClick(element)
                                .perform();
                        break;
                    case "navigate":
                        logger.debug("Navigating to target page '{}'", targetPage);
                        if (element != null) {
                            wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                            wait.until(ExpectedConditions.elementToBeClickable(element));
                            element.click();
                        }
                        String targetPath = ConfigManager.getPageProperty(targetPage, "path");
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(getTimeout(action)))
                                .until(() -> {
                                    if (driver == null) {
                                        throw new IllegalStateException("Driver is null during navigation check");
                                    }
                                    return driver.getCurrentUrl().contains(targetPath);
                                });
                        loadPageElements(targetPage, action);
                        break;
                    case "check":
                        logger.debug("Checking condition for element '{}'", elementId);
                        boolean conditionMet = checkCondition(element, action.get("condition"));
                        int nextIndex = Integer.parseInt(conditionMet
                                ? action.get("ifTrue.nextAction") : action.get("ifFalse.nextAction"));
                        actionIndex.set(nextIndex - 1);
                        return;
                    case "saveState":
                        savedStates.put(action.get("stateKey"), driver.manage().getCookies());
                        logger.debug("Saved state: {}", action.get("stateKey"));
                        break;
                    case "loadState":
                        Set<org.openqa.selenium.Cookie> cookies = savedStates.get(action.get("stateKey"));
                        if (cookies != null) {
                            driver.manage().deleteAllCookies();
                            cookies.forEach(driver.manage()::addCookie);
                            driver.navigate().refresh();
                            logger.debug("Loaded state: {}", action.get("stateKey"));
                        }
                        break;
                    case "kafkaProduce":
                        if (!kafkaEnabled) {
                            logger.info("Kafka is disabled; skipping kafkaProduce action");
                            return;
                        }
                        String topic = action.get("kafka.topic");
                        String key = ConfigManager.resolveParameters(action.get("kafka.key"), data);
                        String message = ConfigManager.resolveParameters(action.get("kafka.value"), data);
                        kafkaProducer.send(new ProducerRecord<>(topic, key, message));
                        kafkaProducer.flush();
                        logger.debug("Produced Kafka message to {}: key={}, value={}", topic, key, message);
                        break;
                    case "kafkaConsume":
                        if (!kafkaEnabled) {
                            logger.info("Kafka is disabled; skipping kafkaConsume action");
                            return;
                        }
                        String consumeTopic = action.get("kafka.topic");
                        String expectedKey = ConfigManager.resolveParameters(action.get("kafka.key"), data);
                        String expectedValueContains = ConfigManager.resolveParameters(action.get("kafka.valueContains"), data);
                        kafkaConsumer.subscribe(Collections.singletonList(consumeTopic));
                        AtomicReference<String> receivedValue = new AtomicReference<>();
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(getTimeout(action)))
                                .until(() -> {
                                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
                                    for (ConsumerRecord<String, String> record : records) {
                                        if (record.key().equals(expectedKey)) {
                                            receivedValue.set(record.value());
                                            return expectedValueContains == null
                                                    || record.value().contains(expectedValueContains);
                                        }
                                    }
                                    return false;
                                });
                        logger.debug("Consumed Kafka message from {}: key={}, value={}",
                                consumeTopic, expectedKey, receivedValue.get());
                        break;
                    case "restCall":
                        String method = action.get("rest.method");
                        String url = ConfigManager.resolveParameters(action.get("rest.url"), data);
                        String body = ConfigManager.resolveParameters(action.get("rest.body"), data);
                        Request.Builder requestBuilder = new Request.Builder().url(url);
                        action.entrySet().stream()
                                .filter(e -> e.getKey().startsWith("rest.header."))
                                .forEach(e -> requestBuilder.addHeader(
                                e.getKey().substring("rest.header.".length()),
                                ConfigManager.resolveParameters(e.getValue(), data)));
                        if (body != null) {
                            requestBuilder.method(method, RequestBody.create(body,
                                    MediaType.parse(action.getOrDefault("rest.header.Content-Type", "application/json"))));
                        } else {
                            requestBuilder.method(method, null);
                        }
                        try {
                            Response response = httpClient.newCall(requestBuilder.build()).execute();
                            assertTrue("REST call failed: " + response.code(), response.isSuccessful());
                            logger.debug("REST {} call to {} returned: {}", method, url, response.code());
                        } catch (IOException e) {
                            logger.error("Failed to execute REST call to {}: {}", url, e.getMessage());
                            throw new RuntimeException("REST call failed", e);
                        }
                        break;
                    case "uploadFile":
                        File file = new File(value);
                        if (!file.exists()) {
                            throw new IllegalArgumentException("File not found: " + value);
                        }
                        logger.debug("Uploading file '{}' to element '{}'", value, elementId);
                        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                        wait.until(ExpectedConditions.elementToBeClickable(element));
                        element.sendKeys(file.getAbsolutePath());
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported action: " + actionType);
                }
            });
            actionIndex.incrementAndGet();
        }
    }

    @Then("results match expected outcomes for test {string}")
    public void resultsMatchExpected(String testId) {
        List<Map<String, String>> testData = ConfigManager.getTestData(testId);
        List<Map<String, String>> assertions = ConfigManager.getTestAssertions(testId);

        if (testData == null) {
            executeAssertions(assertions, null);
        } else {
            for (Map<String, String> dataRow : testData) {
                logger.info("Verifying assertions for test {} with data: {}", testId, dataRow);
                executeAssertions(assertions, dataRow);
            }
        }
    }

    private void executeAssertions(List<Map<String, String>> assertions, Map<String, String> data) {
        for (Map<String, String> assertion : assertions) {
            String pageId = assertion.get("page");
            String type = assertion.get("type");
            String value = ConfigManager.resolveParameters(assertion.get("value"), data);
            String elementId = assertion.getOrDefault("element", null);
            String condition = assertion.get("condition");

            String assertionDescription = String.format("Assertion '%s' on page '%s'%s with expected value '%s' and condition '%s'",
                    type, pageId != null ? pageId : "N/A",
                    elementId != null ? ", element '" + elementId + "'" : "",
                    value, condition);

            executeWithRetry(assertionDescription, () -> {
                logger.debug("Verifying assertion: {}", assertion);
                applyWait(assertion, pageId, elementId, data);

                // Reload page elements before assertion to avoid staleness
                if (elementId != null && pageId != null) {
                    loadPageElements(pageId, assertion);
                }

                switch (type) {
                    case "url":
                        String currentUrl = driver.getCurrentUrl();
                        assertCondition(currentUrl, value, condition, "URL");
                        break;
                    case "visible":
                        WebElement element = pages.get(pageId).get(elementId);
                        boolean isVisible = element.isDisplayed();
                        assertCondition(isVisible, Boolean.parseBoolean(value), condition, "visibility");
                        break;
                    case "text":
                        String text = pages.get(pageId).get(elementId).getText();
                        assertCondition(text, value, condition, "text");
                        break;
                    case "count":
                        int count = driver.findElements(getLocator(pageId, elementId, null)).size();
                        assertCondition(count, Integer.parseInt(value), condition, "count");
                        break;
                    case "enabled":
                        boolean isEnabled = pages.get(pageId).get(elementId).isEnabled();
                        assertCondition(isEnabled, Boolean.parseBoolean(value), condition, "enabled");
                        break;
                    case "attribute":
                        String attr = assertion.get("attributeName");
                        String attrValue = pages.get(pageId).get(elementId).getAttribute(attr);
                        assertCondition(attrValue, value, condition, "attribute " + attr);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported assertion: " + type);
                }
            });
        }
    }

    private void loadPageElements(String pageId, Map<String, String> params) {
        Map<String, Map<String, String>> elements = ConfigManager.getPageElements(pageId);
        Map<String, WebElement> pageElements = new HashMap<>();
        elements.forEach((id, props) -> {
            List<Map<String, String>> locators = ConfigManager.getElementLocators(pageId, id, params);
            WebElement element = null;
            for (Map<String, String> locator : locators) {
                try {
                    By by = getLocator(locator);
                    Awaitility.await()
                            .atMost(Duration.ofSeconds(getTimeout(null)))
                            .until(() -> {
                                List<WebElement> foundElements = driver.findElements(by);
                                logger.debug("Found {} elements for locator {} on page {}", foundElements.size(), by, pageId);
                                return foundElements.size() > 0;
                            });
                    element = driver.findElement(by);
                    break;
                } catch (Exception e) {
                    logger.warn("Failed to find element {} with locator {}, trying next", id, locator, e);
                    continue;
                }
            }
            if (element == null) {
                throw new org.openqa.selenium.NoSuchElementException("No valid locator found for " + id);
            }
            pageElements.put(id, element);
        });
        pages.put(pageId, pageElements);
    }

    private By getLocator(String pageId, String elementId, Map<String, String> params) {
        List<Map<String, String>> locators = ConfigManager.getElementLocators(pageId, elementId, params);
        return getLocator(locators.get(0));
    }

    private By getLocator(Map<String, String> locator) {
        String type = locator.get("type");
        String value = locator.get("value");
        return switch (type) {
            case "id" ->
                By.id(value);
            case "class" ->
                By.className(value);
            case "xpath" ->
                By.xpath(value);
            case "css" ->
                By.cssSelector(value);
            case "name" ->
                By.name(value);
            case "tag" ->
                By.tagName(value);
            default ->
                throw new IllegalArgumentException("Unsupported locator type: " + type);
        };
    }

    private void applyWait(Map<String, String> config, String pageId, String elementId, Map<String, String> data) {
        String waitType = config.get("wait." + (config.containsKey("type") ? "assertion" : "action"));
        if (waitType == null) {
            return;
        }

        int timeout = getTimeout(config);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(timeout));
        By locator = elementId != null ? getLocator(pageId, elementId, config) : null;

        switch (waitType) {
            case "visible":
                Awaitility.await()
                        .atMost(Duration.ofSeconds(timeout))
                        .until(() -> driver.findElement(locator).isDisplayed());
                break;
            case "clickable":
                wait.until(ExpectedConditions.elementToBeClickable(locator));
                break;
            case "present":
                wait.until(ExpectedConditions.presenceOfElementLocated(locator));
                break;
            case "url.contains":
                String urlPart = ConfigManager.resolveParameters(config.get("wait.url.contains"), data);
                wait.until(ExpectedConditions.urlContains(urlPart));
                break;
            case "text.present":
                String text = ConfigManager.resolveParameters(config.get("wait.text"), data);
                wait.until(ExpectedConditions.textToBePresentInElementLocated(locator, text));
                break;
            case "invisible":
                wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));
                break;
            case "staleness":
                WebElement element = pages.get(pageId).get(elementId);
                wait.until(ExpectedConditions.stalenessOf(element));
                break;
            case "custom":
                String script = ConfigManager.resolveParameters(config.get("wait.custom.script"), data);
                wait.until((WebDriver d) -> (Boolean) ((JavascriptExecutor) d).executeScript(script));
                break;
            default:
                throw new IllegalArgumentException("Unsupported wait: " + waitType);
        }
    }

    private boolean checkCondition(WebElement element, String condition) {
        return switch (condition) {
            case "visible" ->
                element.isDisplayed();
            case "enabled" ->
                element.isEnabled();
            case "present" ->
                true;
            default ->
                throw new IllegalArgumentException("Unsupported condition: " + condition);
        };
    }

    private int getTimeout(Map<String, String> config) {
        return config != null && config.containsKey("wait.timeout")
                ? Integer.parseInt(config.get("wait.timeout"))
                : Integer.parseInt(ConfigManager.getConfig("defaultTimeout"));
    }

    private void assertCondition(Object actual, Object expected, String condition, String context) {
        switch (condition) {
            case "equals":
                assertEquals(context + " mismatch", expected, actual);
                break;
            case "contains":
                assertTrue(context + " does not contain " + expected,
                        actual.toString().contains(expected.toString()));
                break;
            case "true":
                assertTrue(context + " not true", (Boolean) actual);
                break;
            case "false":
                assertFalse(context + " not false", (Boolean) actual);
                break;
            case "greaterThan":
                assertTrue(context + " not greater than " + expected,
                        ((Number) actual).doubleValue() > ((Number) expected).doubleValue());
                break;
            case "lessThan":
                assertTrue(context + " not less than " + expected,
                        ((Number) actual).doubleValue() < ((Number) expected).doubleValue());
                break;
            default:
                throw new IllegalArgumentException("Unsupported condition: " + condition);
        }
    }

    private void executeWithRetry(String stepDescription, Runnable action) {
        AtomicReference<Throwable> lastException = new AtomicReference<>();
        logger.info("Attempting step: {}", stepDescription);
        try {
            Awaitility.await()
                    .conditionEvaluationListener(condition -> {
                        if (condition.getRemainingTimeInMS() <= 0) {
                            logger.error("Step '{}' timed out after {} seconds", stepDescription, retryAttempts * (retryDelaySeconds + 1));
                        }
                    })
                    .atMost(Duration.ofSeconds(retryAttempts * (retryDelaySeconds + 1)))
                    .pollInterval(retryDelaySeconds, TimeUnit.SECONDS)
                    .ignoreException(UnreachableBrowserException.class) // Retry on browser crash
                    .until(() -> {
                        try {
                            action.run();
                            logger.debug("Step succeeded: {}", stepDescription);
                            return true;
                        } catch (UnreachableBrowserException e) {
                            logger.warn("Browser unreachable during step '{}', reinitializing driver", stepDescription, e);
                            ChromeOptions options = new ChromeOptions();
                            String chromeArgs = ConfigManager.getConfig("webdriver.chrome.args");
                            if (chromeArgs != null && !chromeArgs.isEmpty()) {
                                options.addArguments(chromeArgs.split(","));
                            }
                            initializeDriver(options);
                            throw e; // Trigger retry
                        } catch (Throwable e) {
                            lastException.set(e);
                            logger.warn("Retry attempt failed for step '{}'", stepDescription, e);
                            return false;
                        }
                    });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            String errorMessage = "Failed step '" + stepDescription + "' timed out after " + (retryAttempts * (retryDelaySeconds + 1)) + " seconds";
            throw new RuntimeException(errorMessage, lastException.get() != null ? lastException.get() : e);
        }
        if (lastException.get() != null) {
            throw new RuntimeException("Failed step '" + stepDescription + "' after " + retryAttempts + " attempts", lastException.get());
        }
    }
}
