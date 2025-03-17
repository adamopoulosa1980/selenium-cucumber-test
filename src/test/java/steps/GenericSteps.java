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

    @Before
    public void setUp(Scenario scenario) {
        this.scenario = scenario;
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        String chromeArgs = ConfigManager.getConfig("webdriver.chrome.args");
        if (chromeArgs != null) {
            options.addArguments(chromeArgs.split(","));
        }
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(
                Long.parseLong(ConfigManager.getConfig("implicitWait")),
                TimeUnit.SECONDS
        );
        retryAttempts = Integer.parseInt(ConfigManager.getConfig("retryAttempts"));
        retryDelaySeconds = Integer.parseInt(ConfigManager.getConfig("retryDelaySeconds"));

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

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(Integer.parseInt(ConfigManager.getConfig("rest.timeout.seconds"))))
                .readTimeout(Duration.ofSeconds(Integer.parseInt(ConfigManager.getConfig("rest.timeout.seconds"))))
                .build();

        logger.info("Starting scenario: {}", scenario.getName());
    }

    @After
    public void tearDown() {
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
            driver.quit();
        }
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    @Given("user is on the {string} page")
    public void userIsOnPage(String pageId) {
        String url = ConfigManager.getConfig("baseUrl") + ConfigManager.getPageProperty(pageId, "path");
        executeWithRetry(() -> {
            logger.debug("Navigating to: {}", url);
            driver.get(url);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(getTimeout(null)))
                    .until(() -> driver.getCurrentUrl().contains(ConfigManager.getPageProperty(pageId, "path")));
            loadPageElements(pageId, null);
        });
    }

    @When("user executes test {string}")
    public void userExecutesTest(String testId) {
        List<Map<String, String>> testData = ConfigManager.getTestData(testId);
        List<Map<String, String>> actions = ConfigManager.getTestActions(testId);

        if (testData == null) {
            executeActions(actions, null);
        } else {
            for (Map<String, String> dataRow : testData) {
                logger.info("Executing test {} with data: {}", testId, dataRow);
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

            executeWithRetry(() -> {
                logger.debug("Executing action: {}", action);
                applyWait(action, pageId, elementId, data);
                if (elementId != null && pageId != null) {
                    loadPageElements(pageId, action);
                }
                WebElement element = elementId != null && pageId != null ? pages.get(pageId).get(elementId) : null;

                switch (actionType) {
                    case "enter":
                        element.clear();
                        element.sendKeys(value);
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> element.getAttribute("value").equals(value));
                        break;
                    case "click":
                        element.click();
                        break;
                    case "select":
                        Select select = new Select(element);
                        select.selectByVisibleText(value);
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> select.getFirstSelectedOption().getText().equals(value));
                        break;
                    case "hover":
                        new org.openqa.selenium.interactions.Actions(driver)
                                .moveToElement(element)
                                .perform();
                        break;
                    case "clear":
                        element.clear();
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(2))
                                .until(() -> element.getAttribute("value").isEmpty());
                        break;
                    case "submit":
                        element.submit();
                        break;
                    case "doubleClick":
                        new org.openqa.selenium.interactions.Actions(driver)
                                .doubleClick(element)
                                .perform();
                        break;
                    case "navigate":
                        element.click();
                        String targetPath = ConfigManager.getPageProperty(targetPage, "path");
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(getTimeout(action)))
                                .until(() -> driver.getCurrentUrl().contains(targetPath));
                        loadPageElements(targetPage, action);
                        break;
                    case "check":
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
                        String topic = action.get("kafka.topic");
                        String key = ConfigManager.resolveParameters(action.get("kafka.key"), data);
                        String message = ConfigManager.resolveParameters(action.get("kafka.value"), data);
                        kafkaProducer.send(new ProducerRecord<>(topic, key, message));
                        kafkaProducer.flush();
                        logger.debug("Produced Kafka message to {}: key={}, value={}", topic, key, message);
                        break;
                    case "kafkaConsume":
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
                        element.sendKeys(file.getAbsolutePath());
                        logger.debug("Uploaded file: {}", value);
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

            executeWithRetry(() -> {
                logger.debug("Verifying assertion: {}", assertion);
                applyWait(assertion, pageId, elementId, data);

                switch (type) {
                    case "url":
                        String currentUrl = driver.getCurrentUrl();
                        assertCondition(currentUrl, value, condition, "URL");
                        break;
                    case "visible":
                        boolean isVisible = pages.get(pageId).get(elementId).isDisplayed();
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
                            .until(() -> driver.findElements(by).size() > 0);
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

    private void executeWithRetry(Runnable action) {
        AtomicReference<Throwable> lastException = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(retryAttempts * (retryDelaySeconds + 1)))
                .pollInterval(retryDelaySeconds, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        action.run();
                        return true;
                    } catch (Throwable e) {
                        lastException.set(e);
                        logger.warn("Retry attempt failed", e);
                        return false;
                    }
                });
        if (lastException.get() != null) {
            throw new RuntimeException("Failed after " + retryAttempts + " attempts", lastException.get());
        }
    }
}
