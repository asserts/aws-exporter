package ai.asserts.aws.cloudwatch.config;

import ai.asserts.aws.ObjectMapperFactory;
import ai.asserts.aws.RateLimiter;
import ai.asserts.aws.cloudwatch.model.CWNamespace;
import ai.asserts.aws.exporter.BasicMetricCollector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.asserts.aws.MetricNameUtil.SCRAPE_ERROR_COUNT_METRIC;
import static ai.asserts.aws.MetricNameUtil.SCRAPE_OPERATION_LABEL;

@Component
@Slf4j
public class ScrapeConfigProvider {
    private final ObjectMapperFactory objectMapperFactory;
    private final BasicMetricCollector metricCollector;
    private final RateLimiter rateLimiter;
    private final String scrapeConfigFile;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Map<String, CWNamespace> byNamespace = new TreeMap<>();
    private final Map<String, CWNamespace> byServiceName = new TreeMap<>();
    private final ResourceLoader resourceLoader = new FileSystemResourceLoader();
    private final ScrapeConfig NOOP_CONFIG = new ScrapeConfig();
    private final RestTemplate restTemplate;
    private final String ASSERT_HOST = "ASSERT_HOST";
    private final String ASSERT_USER = "ASSERT_USER";
    private final String ASSERT_SECRET_KEY = "ASSERT_SECRET_KEY";
    private volatile ScrapeConfig configCache;


    public ScrapeConfigProvider(ObjectMapperFactory objectMapperFactory,
                                BasicMetricCollector metricCollector,
                                RateLimiter rateLimiter,
                                @Value("${scrape.config.file:cloudwatch_scrape_config.yml}") String scrapeConfigFile,
                                RestTemplate restTemplate) {
        this.objectMapperFactory = objectMapperFactory;
        this.scrapeConfigFile = scrapeConfigFile;
        this.metricCollector = metricCollector;
        this.rateLimiter = rateLimiter;
        this.restTemplate = restTemplate;
        loadAndBuildLookups();
    }


    public ScrapeConfig getScrapeConfig() {
        try {
            readWriteLock.readLock().lock();
            return configCache;
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    public void update() {
        loadAndBuildLookups();
    }

    public Optional<CWNamespace> getStandardNamespace(String namespace) {
        return Optional.ofNullable(byNamespace.getOrDefault(namespace,
                byServiceName.getOrDefault(namespace, null)));
    }

    private void loadAndBuildLookups() {
        try {
            readWriteLock.writeLock().lock();
            configCache = load();
            byNamespace.clear();
            byServiceName.clear();
            Stream.of(CWNamespace.values()).forEach(namespace -> {
                byNamespace.put(namespace.getNamespace(), namespace);
                byServiceName.put(namespace.getServiceName(), namespace);
            });
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private ScrapeConfig getConfig(Map<String, String> envVariables) {
        String host = envVariables.get(ASSERT_HOST);
        String user = envVariables.get(ASSERT_USER);
        String key = envVariables.get(ASSERT_SECRET_KEY);
        String url = host + "/api-server/v1/config/aws-exporter";
        log.info("Will load configuration from server [{}] and user [{}]", host, user);
        ResponseEntity<ScrapeConfig> response = restTemplate.exchange(url,
                HttpMethod.GET,
                createAuthHeader(user, key),
                new ParameterizedTypeReference<ScrapeConfig>() {
                });
        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        }
        return NOOP_CONFIG;
    }

    @VisibleForTesting
    HttpEntity<?> createAuthHeader(String username, String password) {
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(username, password);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(headers);
        } else {
            return null;
        }
    }

    private ScrapeConfig load() {
        ScrapeConfig scrapeConfig = NOOP_CONFIG;
        SortedMap<String, String> labels = new TreeMap<>(ImmutableMap.of(SCRAPE_OPERATION_LABEL, "loadConfig"));
        try {
            Map<String, String> envVariables = getGetenv();
            if (envVariables.containsKey(ASSERT_HOST) && envVariables.containsKey(ASSERT_USER)
                    && envVariables.containsKey(ASSERT_SECRET_KEY)) {
                scrapeConfig = getConfig(envVariables);
            } else if (envVariables.containsKey("CONFIG_S3_BUCKET") && envVariables.containsKey("CONFIG_S3_KEY")) {
                labels.put(SCRAPE_OPERATION_LABEL, "loadConfigFromS3");
                try {
                    String bucket = envVariables.get("CONFIG_S3_BUCKET");
                    String key = envVariables.get("CONFIG_S3_KEY");
                    log.info("Will load configuration from S3 Bucket [{}] and Key [{}]", bucket, key);
                    try (S3Client s3Client = getS3Client()) {
                        ResponseBytes<GetObjectResponse> objectAsBytes = rateLimiter.doWithRateLimit(
                                "S3Client/getObjectAsBytes",
                                labels,
                                () -> s3Client.getObjectAsBytes(GetObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(key)
                                        .build()));
                        scrapeConfig = objectMapperFactory.getObjectMapper().readValue(objectAsBytes.asInputStream(), new TypeReference<ScrapeConfig>() {
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to load configuration from S3", e);
                    metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, labels, 1);
                }
            } else {
                log.info("Will load configuration from {}", scrapeConfigFile);
                Resource resource = resourceLoader.getResource(scrapeConfigFile);
                scrapeConfig = objectMapperFactory.getObjectMapper()
                        .readValue(resource.getURL(), new TypeReference<ScrapeConfig>() {
                        });
            }
            scrapeConfig.validateConfig();
            log.info("Loaded configuration");

            if (envVariables.containsKey("REGIONS")) {
                scrapeConfig.setRegions(Stream.of(envVariables.get("REGIONS").split(","))
                        .collect(Collectors.toSet()));
            }

            if (envVariables.containsKey("ENABLE_ECS_SD")) {
                scrapeConfig.setDiscoverECSTasks(isEnabled(System.getenv("ENABLE_ECS_SD")));
            }
            return scrapeConfig;
        } catch (IOException e) {
            log.error("Failed to load scrape configuration from file " + scrapeConfigFile, e);
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, labels, 1);
            return NOOP_CONFIG;
        } catch (Exception e) {
            log.error("Failed to load aws exporter configuration", e);
            metricCollector.recordCounterValue(SCRAPE_ERROR_COUNT_METRIC, labels, 1);
            return NOOP_CONFIG;
        }
    }

    @VisibleForTesting
    Map<String, String> getGetenv() {
        return System.getenv();
    }

    @VisibleForTesting
    S3Client getS3Client() {
        return S3Client.builder().build();
    }

    public boolean isEnabled(String flag) {
        return Stream.of("y", "yes", "true").anyMatch(value -> value.equalsIgnoreCase(flag));
    }
}
