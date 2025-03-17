package utils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;

public class ConfigManager {

    private static Properties configProps = new Properties();
    private static Properties pageProps = new Properties();
    private static Properties testProps = new Properties();
    private static final String ENV = System.getProperty("env", "dev");

    static {
        loadProperties("config." + ENV + ".properties", configProps);
        loadProperties("pages/pages.properties", pageProps);
        loadProperties("testdata/tests.properties", testProps);
    }

    private static void loadProperties(String fileName, Properties props) {
        try (InputStream input = ConfigManager.class.getClassLoader().getResourceAsStream(fileName)) {
            if (input != null) {
                props.load(input);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + fileName, e);
        }
    }

    public static String getConfig(String key) {
        return configProps.getProperty(key);
    }

    public static String getPageProperty(String page, String key) {
        return pageProps.getProperty("page." + page + "." + key);
    }

    public static Map<String, Map<String, String>> getPageElements(String page) {
        Map<String, Map<String, String>> elements = new HashMap<>();
        String prefix = "page." + page + ".elements.";
        pageProps.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> {
                    String[] parts = key.split("\\.");
                    String elementName = parts[3];
                    String property = key.substring(prefix.length() + elementName.length() + 1);
                    elements.computeIfAbsent(elementName, k -> new HashMap<>())
                            .put(property, pageProps.getProperty(key));
                });
        return elements;
    }

    public static List<Map<String, String>> getElementLocators(String page, String element, Map<String, String> params) {
        List<Map<String, String>> locators = new ArrayList<>();
        String prefix = "page." + page + ".elements." + element + ".locator[";
        pageProps.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> {
                    Map<String, String> map = new HashMap<>();
                    String index = key.substring(prefix.length(), key.indexOf("]"));
                    map.put("index", index);
                    String property = key.substring(key.indexOf("].") + 2);
                    String value = resolveParameters(pageProps.getProperty(key), params);
                    map.put(property, value);
                    return map;
                })
                .collect(Collectors.groupingBy(m -> m.get("index")))
                .forEach((index, maps) -> {
                    Map<String, String> combined = new HashMap<>();
                    maps.forEach(combined::putAll);
                    locators.add(combined);
                });
        locators.sort(Comparator.comparing(m -> Integer.parseInt(m.get("index"))));
        return locators;
    }

    public static List<Map<String, String>> getTestActions(String testId) {
        return getIndexedProperties(testId, "actions");
    }

    public static List<Map<String, String>> getTestAssertions(String testId) {
        return getIndexedProperties(testId, "assertions");
    }

    private static List<Map<String, String>> getIndexedProperties(String testId, String type) {
        List<Map<String, String>> result = new ArrayList<>();
        String prefix = "test." + testId + "." + type + "[";
        testProps.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> {
                    Map<String, String> map = new HashMap<>();
                    String index = key.substring(prefix.length(), key.indexOf("]"));
                    map.put("index", index);
                    String property = key.substring(key.indexOf("].") + 2);
                    map.put(property, resolveParameters(testProps.getProperty(key), null));
                    return map;
                })
                .collect(Collectors.groupingBy(m -> m.get("index")))
                .forEach((index, maps) -> {
                    Map<String, String> combined = new HashMap<>();
                    maps.forEach(combined::putAll);
                    result.add(combined);
                });
        result.sort(Comparator.comparing(m -> Integer.parseInt(m.get("index"))));
        return result;
    }

    public static List<Map<String, String>> getTestData(String testId) {
        String dataFile = testProps.getProperty("test." + testId + ".dataFile");
        if (dataFile == null) {
            return null;
        }

        if (dataFile.endsWith(".csv")) {
            return readCsvData(dataFile);
        } else if (dataFile.endsWith(".json")) {
            return readJsonData(dataFile);
        }
        throw new IllegalArgumentException("Unsupported data file format: " + dataFile);
    }

    private static List<Map<String, String>> readCsvData(String fileName) {
        List<Map<String, String>> data = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                ConfigManager.class.getClassLoader().getResourceAsStream(fileName)))) {
            String[] headers = reader.readNext();
            String[] line;
            while ((line = reader.readNext()) != null) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i], line[i]);
                }
                data.add(row);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CSV: " + fileName, e);
        }
        return data;
    }

    private static List<Map<String, String>> readJsonData(String fileName) {
        try (InputStream input = ConfigManager.class.getClassLoader().getResourceAsStream(fileName)) {
            ObjectMapper mapper = new ObjectMapper();
            return Arrays.asList(mapper.readValue(input, Map[].class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read JSON: " + fileName, e);
        }
    }

    public static Set<String> getTestIds() {
        Set<String> testIds = new HashSet<>();
        testProps.stringPropertyNames().forEach(key -> {
            String[] parts = key.split("\\.");
            if (parts.length > 1 && parts[0].equals("test")) {
                testIds.add(parts[1]);
            }
        });
        return testIds;
    }

    public static String resolveParameters(String value, Map<String, String> additionalParams) {
        if (value == null || !value.contains("${")) {
            return value; // Quick exit if no parameters
        }
        String result = value;
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}"); // Simplified pattern
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String paramKey = matcher.group(1);
            String paramValue = null;
            if (paramKey.startsWith("param.")) {
                paramValue = testProps.getProperty(paramKey);
            } else if (additionalParams != null && paramKey.startsWith("data.")) {
                paramValue = additionalParams.get(paramKey.substring(5));
            }
            if (paramValue != null) {
                result = result.replace("${" + paramKey + "}", paramValue);
            }
        }
        return result;
    }
}
