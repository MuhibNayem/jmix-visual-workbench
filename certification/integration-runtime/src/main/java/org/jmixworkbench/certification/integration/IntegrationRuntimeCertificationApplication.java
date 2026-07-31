package org.jmixworkbench.certification.integration;

import com.acme.cert.integration.DocumentDownloadConnector;
import com.acme.cert.integration.DocumentUploadConnector;
import com.acme.cert.integration.HrmsPartnerClient;
import com.acme.cert.integration.LoanEventConsumer;
import com.acme.cert.integration.LoanEventHandler;
import com.acme.cert.integration.LoanEventPublisher;
import com.acme.cert.integration.PayrollEventConsumer;
import com.acme.cert.integration.PayrollEventPublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.jmix.core.annotation.JmixModule;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.core.security.UserRepository;
import io.jmix.core.security.impl.SystemAuthenticationProvider;
import io.micrometer.core.instrument.MeterRegistry;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@SpringBootApplication
@JmixModule(id = "org.jmixworkbench.certification.integration")
@EnableKafka
@EnableRabbit
@Import({
        DocumentDownloadConnector.class,
        DocumentUploadConnector.class,
        HrmsPartnerClient.class,
        LoanEventConsumer.class,
        LoanEventHandler.class,
        LoanEventPublisher.class,
        PayrollEventConsumer.class,
        PayrollEventPublisher.class
})
public class IntegrationRuntimeCertificationApplication {
    private static final String KAFKA_TOPIC = "jvw-cert-loan-events";
    private static final String RABBIT_QUEUE = "jvw-cert-payroll-events";
    private static final String KAFKA_INBOUND_TOPIC = "jvw-cert-loan-inbound";
    private static final String KAFKA_INBOUND_DLT = "jvw-cert-loan-inbound-dlt";
    private static final String RABBIT_INBOUND_QUEUE = "jvw-cert-payroll-inbound";
    private static final String RABBIT_INBOUND_DLT = "jvw-cert-payroll-inbound-dlt";
    private static final String KAFKA_TABLE = "jvw_loan_event_outbox";
    private static final String RABBIT_TABLE = "jvw_payroll_event_outbox";
    private static final String KAFKA_INBOX = "jvw_loan_event_inbox";
    private static final String RABBIT_INBOX = "jvw_payroll_event_inbox";
    private static final String HANDLER_EFFECT = "jvw_cert_handler_effect";

    @Bean(name = "dataSource")
    @Primary
    DataSource dataSource(RuntimeTarget target) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("JmixIntegrationCertification");
        configuration.setJdbcUrl(target.databaseUrl());
        configuration.setUsername(target.databaseUsername());
        configuration.setPassword(target.databasePassword());
        configuration.setDriverClassName("org.postgresql.Driver");
        configuration.setMaximumPoolSize(6);
        configuration.setMinimumIdle(1);
        configuration.setConnectionTimeout(5_000);
        return new HikariDataSource(configuration);
    }

    @Bean(name = "transactionManager")
    @Primary
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:org/jmixworkbench/certification/integration/liquibase/changelog.xml");
        liquibase.setDropFirst(false);
        return liquibase;
    }

    @Bean
    NewTopic certificationKafkaTopic() {
        return TopicBuilder.name(KAFKA_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic certificationKafkaInboundTopic() {
        return TopicBuilder.name(KAFKA_INBOUND_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic certificationKafkaInboundDeadLetterTopic() {
        return TopicBuilder.name(KAFKA_INBOUND_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    KafkaAdmin kafkaAdmin(RuntimeTarget target) {
        return new KafkaAdmin(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                target.kafkaBootstrap()));
    }

    @Bean
    ProducerFactory<String, String> kafkaProducerFactory(RuntimeTarget target) {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, target.kafkaBootstrap(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_000));
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean(name = "objectMapper")
    VersionedObjectMapperFactoryBean objectMapper() {
        return new VersionedObjectMapperFactoryBean();
    }

    @Bean
    Queue certificationRabbitQueue() {
        return new Queue(RABBIT_QUEUE, false, false, false);
    }

    @Bean
    Queue certificationRabbitInboundQueue() {
        return new Queue(RABBIT_INBOUND_QUEUE, false, false, false);
    }

    @Bean
    Queue certificationRabbitInboundDeadLetterQueue() {
        return new Queue(RABBIT_INBOUND_DLT, false, false, false);
    }

    @Bean
    SftpRemoteFileTemplate sftpTemplate(RuntimeTarget target) {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(true);
        factory.setHost(target.sftpHost());
        factory.setPort(target.sftpPort());
        factory.setUser(target.sftpUsername());
        factory.setPassword(target.sftpPassword());
        factory.setAllowUnknownKeys(true);
        factory.setSshClientConfigurer(client ->
                client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE)));
        return new SftpRemoteFileTemplate(factory);
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    RuntimeTarget runtimeTarget() {
        return RuntimeTarget.fromEnvironment();
    }

    @Bean
    AuthenticationManager authenticationManager(UserRepository userRepository) {
        return new ProviderManager(new SystemAuthenticationProvider(userRepository));
    }

    @Bean
    UserRepository userRepository() {
        UserDetails system = User.withUsername("system")
                .password("{noop}runtime-certification")
                .authorities("ROLE_SYSTEM")
                .build();
        UserDetails anonymous = User.withUsername("anonymous")
                .password("{noop}runtime-certification")
                .authorities("ROLE_ANONYMOUS")
                .build();
        return new UserRepository() {
            @Override
            public UserDetails getSystemUser() {
                return system;
            }

            @Override
            public UserDetails getAnonymousUser() {
                return anonymous;
            }

            @Override
            public List<? extends UserDetails> getByUsernameLike(String substring) {
                return system.getUsername().contains(substring) ? List.of(system) : List.of();
            }

            @Override
            public UserDetails loadUserByUsername(String username) {
                if (system.getUsername().equalsIgnoreCase(username)) {
                    return system;
                }
                throw new UsernameNotFoundException(username);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        RuntimeTarget target = RuntimeTarget.fromEnvironment();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.kafka.bootstrap-servers", target.kafkaBootstrap());
        properties.put("spring.rabbitmq.host", target.rabbitHost());
        properties.put("spring.rabbitmq.port", target.rabbitPort());
        properties.put("spring.kafka.admin.fail-fast", "true");

        long startedAt = System.nanoTime();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                IntegrationRuntimeCertificationApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run(args)) {
            CertificationEvidence evidence = certify(context, target, startedAt);
            evidence.write(target.evidenceFile());
            System.out.println("JVW_INTEGRATION_CERTIFICATION_OK " + evidence.toJson());
        }
    }

    private static CertificationEvidence certify(
            ConfigurableApplicationContext context,
            RuntimeTarget target,
            long startedAt
    ) throws Exception {
        LoanEventPublisher kafkaPublisher = context.getBean(LoanEventPublisher.class);
        PayrollEventPublisher rabbitPublisher = context.getBean(PayrollEventPublisher.class);
        DocumentUploadConnector documentUpload =
                context.getBean(DocumentUploadConnector.class);
        DocumentDownloadConnector documentDownload =
                context.getBean(DocumentDownloadConnector.class);
        HrmsPartnerClient hrmsPartnerClient = context.getBean(HrmsPartnerClient.class);
        SftpRemoteFileTemplate sftpTemplate =
                context.getBean(SftpRemoteFileTemplate.class);
        RabbitTemplate rabbitTemplate = context.getBean(RabbitTemplate.class);
        KafkaTemplate<String, String> kafkaTemplate = context.getBean(KafkaTemplate.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        SystemAuthenticator systemAuthenticator = context.getBean(SystemAuthenticator.class);
        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
        CircuitBreaker hrmsCircuitBreaker = context
                .getBean(CircuitBreakerRegistry.class)
                .circuitBreaker("hrmsPartnerClient");

        require(!Modifier.isFinal(LoanEventPublisher.class.getModifiers()),
                "Generated Kafka connector blocks Spring class proxies");
        require(!Modifier.isFinal(PayrollEventPublisher.class.getModifiers()),
                "Generated Rabbit connector blocks Spring class proxies");
        require(tableExists(jdbc, KAFKA_TABLE)
                        && tableExists(jdbc, RABBIT_TABLE)
                        && tableExists(jdbc, KAFKA_INBOX)
                        && tableExists(jdbc, RABBIT_INBOX)
                        && tableExists(jdbc, HANDLER_EFFECT),
                "Generated Liquibase outbox/inbox migrations were not applied");

        jdbc.update("DELETE FROM " + KAFKA_TABLE);
        jdbc.update("DELETE FROM " + RABBIT_TABLE);
        jdbc.update("DELETE FROM " + KAFKA_INBOX);
        jdbc.update("DELETE FROM " + RABBIT_INBOX);
        jdbc.update("DELETE FROM " + HANDLER_EFFECT);
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RABBIT_QUEUE);
            channel.queuePurge(RABBIT_INBOUND_QUEUE);
            channel.queuePurge(RABBIT_INBOUND_DLT);
            return null;
        });

        InboundCertification inbound = certifyInbound(
                context,
                target,
                jdbc,
                kafkaTemplate,
                rabbitTemplate,
                systemAuthenticator);

        try (KafkaConsumer<String, String> consumer = kafkaConsumer(target)) {
            consumer.subscribe(Collections.singleton(KAFKA_TOPIC));
            awaitAssignment(consumer);

            List<String> expectedKafka = List.of("loan-1", "loan-2", "loan-3");
            expectedKafka.forEach(payload -> kafkaPublisher.enqueue("loan-A", payload));
            List<String> expectedRabbit = List.of("payroll-1", "payroll-2");
            expectedRabbit.forEach(payload -> rabbitPublisher.enqueue("payroll-A", payload));
            kafkaPublisher.dispatchOutbox();
            rabbitPublisher.dispatchOutbox();

            List<ConsumerRecord<String, String>> baselineKafka =
                    receiveKafka(consumer, expectedKafka.size(), Duration.ofSeconds(15));
            require(baselineKafka.stream().map(ConsumerRecord::value).toList().equals(expectedKafka),
                    "Kafka per-key ordering or payload integrity failed");
            require(baselineKafka.stream().allMatch(record ->
                            record.headers().lastHeader("jvw-outbox-id") != null),
                    "Kafka stable outbox ID header is missing");
            require(receiveRabbit(rabbitTemplate, expectedRabbit.size()).equals(expectedRabbit),
                    "RabbitMQ delivery order or payload integrity failed");
            require(sentCount(jdbc, KAFKA_TABLE) == 3 && sentCount(jdbc, RABBIT_TABLE) == 2,
                    "Baseline broker acknowledgements were not persisted");

            configureWireMock(target, WireMockMode.SUCCESS);
            require("accepted".equals(
                            hrmsPartnerClient.exchange("payroll-request", "payroll-2026-07")),
                    "HTTP provider contract, API key, or idempotency header failed");
            configureWireMock(target, WireMockMode.DELAYED);
            long timeoutStartedAt = System.nanoTime();
            boolean timedOut = false;
            try {
                hrmsPartnerClient.exchange("delayed-request", "payroll-timeout");
            } catch (RuntimeException expected) {
                timedOut = true;
            }
            long timeoutDurationMs = (System.nanoTime() - timeoutStartedAt) / 1_000_000L;
            require(timedOut && timeoutDurationMs >= 500 && timeoutDurationMs < 3_000,
                    "HTTP provider timeout was not bounded by the generated policy");
            hrmsCircuitBreaker.reset();
            configureWireMock(target, WireMockMode.TRANSIENT_FAILURE);
            require("accepted".equals(
                            hrmsPartnerClient.exchange("retry-request", "payroll-retry")),
                    "HTTP retry did not recover from a transient provider failure");
            require(wireMockRequestCount(target) == 2,
                    "HTTP retry policy did not make exactly one bounded retry");

            hrmsCircuitBreaker.reset();
            configureWireMock(target, WireMockMode.FAILURE);
            int failedCalls = 0;
            while (hrmsCircuitBreaker.getState() != CircuitBreaker.State.OPEN
                    && failedCalls < 4) {
                try {
                    hrmsPartnerClient.exchange("breaker-request", "payroll-breaker");
                } catch (RuntimeException expected) {
                    failedCalls++;
                }
            }
            require(hrmsCircuitBreaker.getState() == CircuitBreaker.State.OPEN,
                    "HTTP circuit breaker did not open after its configured failure window");
            int requestsBeforeFailFast = wireMockRequestCount(target);
            requireThrowsRuntime(
                    () -> hrmsPartnerClient.exchange(
                            "breaker-request", "payroll-breaker-fast"),
                    "HTTP circuit breaker did not fail fast");
            require(wireMockRequestCount(target) == requestsBeforeFailFast,
                    "Open HTTP circuit breaker still called the failed provider");

            byte[] document = "certified-payroll-document".getBytes(StandardCharsets.UTF_8);
            documentUpload.upload("payroll-proof.txt", document);
            require(Arrays.equals(documentDownload.download("payroll-proof.txt"), document),
                    "SFTP atomic upload/download corrupted the binary payload");
            Boolean noTemporaryFile = sftpTemplate.execute(session -> {
                for (var entry : session.list("/upload")) {
                    if (entry.getFilename().contains(".jvw-")
                            || entry.getFilename().endsWith(".writing")) {
                        return false;
                    }
                }
                return true;
            });
            require(Boolean.TRUE.equals(noTemporaryFile),
                    "SFTP atomic upload leaked a temporary file");
            requireThrowsIllegalArgument(
                    () -> documentUpload.upload("../escape.txt", document),
                    "SFTP path traversal was not rejected");

            String outageKafkaId = kafkaPublisher.enqueue("loan-outage", "loan-outage");
            String outageRabbitId = rabbitPublisher.enqueue("payroll-outage", "payroll-outage");
            setProxy(target, "kafka", false);
            setProxy(target, "rabbit", false);
            try {
                kafkaPublisher.dispatchOutbox();
                rabbitPublisher.dispatchOutbox();
            } finally {
                setProxy(target, "kafka", true);
                setProxy(target, "rabbit", true);
            }
            require(status(jdbc, KAFKA_TABLE, outageKafkaId).equals("RETRY"),
                    "Kafka outage did not persist a retry state");
            require(status(jdbc, RABBIT_TABLE, outageRabbitId).equals("RETRY"),
                    "Rabbit outage did not persist a retry state");
            require(attempts(jdbc, KAFKA_TABLE, outageKafkaId) == 1
                            && attempts(jdbc, RABBIT_TABLE, outageRabbitId) == 1,
                    "Broker outage attempt accounting is incorrect");

            Thread.sleep(1_200);
            kafkaPublisher.dispatchOutbox();
            rabbitPublisher.dispatchOutbox();
            List<ConsumerRecord<String, String>> recoveredKafka =
                    receiveKafka(consumer, 1, Duration.ofSeconds(15));
            require(recoveredKafka.stream().allMatch(record ->
                            record.value().equals("loan-outage")),
                    "Kafka recovery did not deliver the retained event");
            require(recoveredKafka.stream().allMatch(record ->
                            outageKafkaId.equals(outboxId(record))),
                    "Kafka retry did not preserve its stable idempotency identifier");
            require(receiveRabbit(rabbitTemplate, 1).equals(List.of("payroll-outage")),
                    "Rabbit recovery did not deliver the retained event");

            String deadId = kafkaPublisher.enqueue("loan-blocked", "loan-dead-first");
            String blockedId = kafkaPublisher.enqueue("loan-blocked", "loan-after-dead");
            jdbc.update("UPDATE " + KAFKA_TABLE
                    + " SET status = 'DEAD', attempts = 3 WHERE id = ?", deadId);
            kafkaPublisher.dispatchOutbox();
            require(status(jdbc, KAFKA_TABLE, blockedId).equals("PENDING"),
                    "Strict ordering allowed an event to pass a terminal predecessor");
            require(kafkaPublisher.reconcile().orderingBlocked() >= 1,
                    "Reconciliation did not report the ordering blockage");
            systemAuthenticator.runWithSystem(() ->
                    kafkaPublisher.replay(deadId, "runtime certification replay"));
            kafkaPublisher.dispatchOutbox();
            kafkaPublisher.dispatchOutbox();
            List<String> replayed = receiveKafka(consumer, 2, Duration.ofSeconds(15)).stream()
                    .map(ConsumerRecord::value)
                    .toList();
            require(replayed.equals(List.of("loan-dead-first", "loan-after-dead")),
                    "Replay did not restore strict per-key order");

            String checksumId = kafkaPublisher.enqueue("loan-checksum", "checksum-original");
            jdbc.update("UPDATE " + KAFKA_TABLE + " SET payload = ? WHERE id = ?",
                    "\"checksum-tampered\"", checksumId);
            kafkaPublisher.dispatchOutbox();
            require(status(jdbc, KAFKA_TABLE, checksumId).equals("RETRY"),
                    "Payload checksum corruption was not rejected");
            require(lastError(jdbc, KAFKA_TABLE, checksumId)
                            .contains("checksum mismatch"),
                    "Checksum failure was not recorded safely");

            String leaseId = rabbitPublisher.enqueue("payroll-lease", "lease-recovery");
            jdbc.update("UPDATE " + RABBIT_TABLE
                            + " SET status = 'IN_FLIGHT', locked_by = 'crashed-node', locked_until = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(30)), leaseId);
            rabbitPublisher.dispatchOutbox();
            require(receiveRabbit(rabbitTemplate, 1).equals(List.of("lease-recovery")),
                    "Expired outbox lease was not reclaimed");

            double kafkaDelivered = meterRegistry
                    .counter("jvw.integration.outbox.events",
                            "connector", "loanEventPublisher",
                            "kind", "kafka_publisher",
                            "outcome", "delivered")
                    .count();
            require(kafkaDelivered >= 6,
                    "Generated Micrometer delivery counter was not emitted");

            return new CertificationEvidence(
                    target.cellId(),
                    System.getProperty("cert.jmix.version"),
                    Runtime.version().feature(),
                    (System.nanoTime() - startedAt) / 1_000_000L,
                    baselineKafka.size(),
                    expectedRabbit.size(),
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    recoveredKafka.size(),
                    true,
                    true,
                    true,
                    kafkaDelivered,
                    inbound.kafkaScenarios(),
                    inbound.rabbitScenarios(),
                    inbound.missingIdentityQuarantined(),
                    inbound.conflictingIdentityRejected(),
                    inbound.transactionalEffectsCertified()
            );
        }
    }

    private static InboundCertification certifyInbound(
            ConfigurableApplicationContext context,
            RuntimeTarget target,
            JdbcTemplate jdbc,
            KafkaTemplate<String, String> kafkaTemplate,
            RabbitTemplate rabbitTemplate,
            SystemAuthenticator systemAuthenticator
    ) throws Exception {
        LoanEventConsumer kafkaConsumerConnector = context.getBean(LoanEventConsumer.class);
        PayrollEventConsumer rabbitConsumerConnector =
                context.getBean(PayrollEventConsumer.class);
        LoanEventHandler handler = context.getBean(LoanEventHandler.class);

        try (KafkaConsumer<String, String> dltConsumer =
                     kafkaConsumer(target, "jvw-cert-inbound-dlt-" + UUID.randomUUID())) {
            dltConsumer.subscribe(Collections.singleton(KAFKA_INBOUND_DLT));
            awaitAssignment(dltConsumer);

            String kafkaDuplicateId = "kafka-duplicate-1";
            String kafkaDuplicatePayload = "ok:kafka-duplicate";
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC,
                    kafkaDuplicateId, kafkaDuplicatePayload);
            awaitStatus(jdbc, KAFKA_INBOX, kafkaDuplicateId, "DONE");
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC,
                    kafkaDuplicateId, kafkaDuplicatePayload);
            awaitCondition(
                    () -> handler.attempts(kafkaDuplicatePayload) == 1,
                    Duration.ofSeconds(5),
                    "Kafka successful duplicate invoked the handler twice");
            require(effectCount(jdbc, kafkaDuplicatePayload) == 1,
                    "Kafka duplicate created more than one transactional business effect");

            String kafkaCollisionPayload = "ok:kafka-collision";
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC,
                    kafkaDuplicateId, kafkaCollisionPayload);
            ConsumerRecord<String, String> kafkaCollisionDlt =
                    receiveKafka(dltConsumer, 1, Duration.ofSeconds(15)).get(0);
            require(kafkaCollisionPayload.equals(kafkaCollisionDlt.value()),
                    "Kafka conflicting message identity was not routed to DLT");
            require(status(jdbc, KAFKA_INBOX, kafkaDuplicateId).equals("DONE")
                            && effectCount(jdbc, kafkaCollisionPayload) == 0,
                    "Kafka identity collision overwrote a completed inbox event");

            String kafkaRetryId = "kafka-retry-1";
            String kafkaRetryPayload = "retry-once:kafka";
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC, kafkaRetryId, kafkaRetryPayload);
            awaitStatus(jdbc, KAFKA_INBOX, kafkaRetryId, "DONE");
            require(handler.attempts(kafkaRetryPayload) == 2
                            && effectCount(jdbc, kafkaRetryPayload) == 1,
                    "Kafka retry did not preserve one transactional business effect");

            String kafkaPoisonId = "kafka-poison-1";
            String kafkaPoisonPayload = "poison:kafka";
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC, kafkaPoisonId, kafkaPoisonPayload);
            ConsumerRecord<String, String> kafkaPoisonDlt =
                    receiveKafka(dltConsumer, 1, Duration.ofSeconds(15)).get(0);
            awaitStatus(jdbc, KAFKA_INBOX, kafkaPoisonId, "DEAD");
            require(kafkaPoisonPayload.equals(kafkaPoisonDlt.value())
                            && handler.attempts(kafkaPoisonPayload) == 3
                            && retainedPayload(jdbc, KAFKA_INBOX, kafkaPoisonId) != null,
                    "Kafka poison message retry, DLT, or terminal retention failed");

            long kafkaDeadBeforeMissing = countStatus(jdbc, KAFKA_INBOX, "DEAD");
            sendKafka(kafkaTemplate, KAFKA_INBOUND_TOPIC, null, "missing-id:kafka");
            ConsumerRecord<String, String> kafkaMissingDlt =
                    receiveKafka(dltConsumer, 1, Duration.ofSeconds(15)).get(0);
            awaitCondition(
                    () -> countStatus(jdbc, KAFKA_INBOX, "DEAD")
                            == kafkaDeadBeforeMissing + 1,
                    Duration.ofSeconds(15),
                    "Kafka missing-ID message was not quarantined");
            require(outboxId(kafkaMissingDlt).startsWith("quarantine-"),
                    "Kafka missing-ID DLT record lacks a non-forgeable quarantine identity");

            handler.releasePoison(kafkaPoisonPayload);
            systemAuthenticator.runWithSystem(() ->
                    kafkaConsumerConnector.replay(
                            kafkaPoisonId, "certified Kafka poison replay"));
            awaitStatus(jdbc, KAFKA_INBOX, kafkaPoisonId, "DONE");
            require(effectCount(jdbc, kafkaPoisonPayload) == 1,
                    "Kafka replay did not create exactly one transactional business effect");
        }

        String rabbitDuplicateId = "rabbit-duplicate-1";
        String rabbitDuplicatePayload = "ok:rabbit-duplicate";
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE,
                rabbitDuplicateId, rabbitDuplicatePayload);
        awaitStatus(jdbc, RABBIT_INBOX, rabbitDuplicateId, "DONE");
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE,
                rabbitDuplicateId, rabbitDuplicatePayload);
        awaitCondition(
                () -> handler.attempts(rabbitDuplicatePayload) == 1,
                Duration.ofSeconds(5),
                "Rabbit successful duplicate invoked the handler twice");
        require(effectCount(jdbc, rabbitDuplicatePayload) == 1,
                "Rabbit duplicate created more than one transactional business effect");

        String rabbitCollisionPayload = "ok:rabbit-collision";
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE,
                rabbitDuplicateId, rabbitCollisionPayload);
        require(rabbitCollisionPayload.equals(
                        receiveRabbit(rabbitTemplate, RABBIT_INBOUND_DLT, 1).get(0)),
                "Rabbit conflicting message identity was not routed to DLT");
        require(status(jdbc, RABBIT_INBOX, rabbitDuplicateId).equals("DONE")
                        && effectCount(jdbc, rabbitCollisionPayload) == 0,
                "Rabbit identity collision overwrote a completed inbox event");

        String rabbitRetryId = "rabbit-retry-1";
        String rabbitRetryPayload = "retry-once:rabbit";
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE, rabbitRetryId, rabbitRetryPayload);
        awaitStatus(jdbc, RABBIT_INBOX, rabbitRetryId, "DONE");
        require(handler.attempts(rabbitRetryPayload) == 2
                        && effectCount(jdbc, rabbitRetryPayload) == 1,
                "Rabbit retry did not preserve one transactional business effect");

        String rabbitPoisonId = "rabbit-poison-1";
        String rabbitPoisonPayload = "poison:rabbit";
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE, rabbitPoisonId, rabbitPoisonPayload);
        require(rabbitPoisonPayload.equals(
                        receiveRabbit(rabbitTemplate, RABBIT_INBOUND_DLT, 1).get(0)),
                "Rabbit poison message was not routed to DLT");
        awaitStatus(jdbc, RABBIT_INBOX, rabbitPoisonId, "DEAD");
        require(handler.attempts(rabbitPoisonPayload) == 3
                        && retainedPayload(jdbc, RABBIT_INBOX, rabbitPoisonId) != null,
                "Rabbit poison retry or terminal payload retention failed");

        long rabbitDeadBeforeMissing = countStatus(jdbc, RABBIT_INBOX, "DEAD");
        sendRabbit(rabbitTemplate, RABBIT_INBOUND_QUEUE, null, "missing-id:rabbit");
        require("missing-id:rabbit".equals(
                        receiveRabbit(rabbitTemplate, RABBIT_INBOUND_DLT, 1).get(0)),
                "Rabbit missing-ID message was not routed to DLT");
        awaitCondition(
                () -> countStatus(jdbc, RABBIT_INBOX, "DEAD")
                        == rabbitDeadBeforeMissing + 1,
                Duration.ofSeconds(15),
                "Rabbit missing-ID message was not quarantined");

        handler.releasePoison(rabbitPoisonPayload);
        systemAuthenticator.runWithSystem(() ->
                rabbitConsumerConnector.replay(
                        rabbitPoisonId, "certified Rabbit poison replay"));
        awaitStatus(jdbc, RABBIT_INBOX, rabbitPoisonId, "DONE");
        require(effectCount(jdbc, rabbitPoisonPayload) == 1,
                "Rabbit replay did not create exactly one transactional business effect");

        return new InboundCertification(6, 6, true, true, true);
    }

    private static KafkaConsumer<String, String> kafkaConsumer(RuntimeTarget target) {
        return kafkaConsumer(target, "jvw-cert-" + UUID.randomUUID());
    }

    private static KafkaConsumer<String, String> kafkaConsumer(
            RuntimeTarget target,
            String groupId
    ) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, target.kafkaBootstrap());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private static void sendKafka(
            KafkaTemplate<String, String> template,
            String topic,
            String messageId,
            String payload
    ) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, payload);
        if (messageId != null) {
            record.headers().add(
                    "jvw-outbox-id",
                    messageId.getBytes(StandardCharsets.UTF_8));
        }
        template.send(record).get();
    }

    private static void sendRabbit(
            RabbitTemplate template,
            String queue,
            String messageId,
            String payload
    ) {
        template.convertAndSend(queue, payload, message -> {
            if (messageId != null) {
                message.getMessageProperties().setMessageId(messageId);
                message.getMessageProperties().setHeader("jvw-outbox-id", messageId);
            }
            return message;
        });
    }

    private static void awaitAssignment(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250));
        }
        require(!consumer.assignment().isEmpty(), "Kafka consumer did not receive a partition");
    }

    private static List<ConsumerRecord<String, String>> receiveKafka(
            KafkaConsumer<String, String> consumer,
            int expected,
            Duration timeout
    ) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (records.size() < expected && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
        }
        require(records.size() >= expected,
                "Expected at least " + expected + " Kafka records but received " + records.size());
        return records;
    }

    private static String outboxId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("jvw-outbox-id");
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<String> receiveRabbit(RabbitTemplate template, int expected) {
        return receiveRabbit(template, RABBIT_QUEUE, expected);
    }

    private static List<String> receiveRabbit(
            RabbitTemplate template,
            String queue,
            int expected
    ) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < expected; index++) {
            Object value = template.receiveAndConvert(queue, 10_000);
            require(value instanceof String, "RabbitMQ message was missing or had the wrong type");
            values.add((String) value);
        }
        return values;
    }

    private static void awaitStatus(
            JdbcTemplate jdbc,
            String table,
            String id,
            String expected
    ) throws InterruptedException {
        awaitCondition(
                () -> expected.equals(statusOrNull(jdbc, table, id)),
                Duration.ofSeconds(15),
                "Timed out waiting for " + table + " event " + id
                        + " to reach " + expected);
    }

    private static void awaitCondition(
            BooleanSupplier condition,
            Duration timeout,
            String failureMessage
    ) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        require(condition.getAsBoolean(), failureMessage);
    }

    private static void setProxy(RuntimeTarget target, String proxy, boolean enabled)
            throws Exception {
        String body = "{\"enabled\":" + enabled + "}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(target.toxiproxyUrl() + "/proxies/" + proxy))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() >= 200 && response.statusCode() < 300,
                "Could not change Toxiproxy state for " + proxy + ": " + response.statusCode());
    }

    private static void configureWireMock(RuntimeTarget target, WireMockMode mode)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest reset = HttpRequest.newBuilder(
                        URI.create(target.wiremockUrl() + "/__admin/mappings"))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        HttpResponse<String> resetResponse =
                client.send(reset, HttpResponse.BodyHandlers.ofString());
        require(resetResponse.statusCode() >= 200 && resetResponse.statusCode() < 300,
                "WireMock mappings could not be reset");
        HttpRequest resetRequests = HttpRequest.newBuilder(
                        URI.create(target.wiremockUrl() + "/__admin/requests"))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        HttpResponse<String> resetRequestsResponse =
                client.send(resetRequests, HttpResponse.BodyHandlers.ofString());
        require(resetRequestsResponse.statusCode() >= 200
                        && resetRequestsResponse.statusCode() < 300,
                "WireMock request journal could not be reset");

        if (mode == WireMockMode.TRANSIENT_FAILURE) {
            addWireMockMapping(client, target, "{"
                    + "\"scenarioName\":\"bounded-retry\","
                    + "\"requiredScenarioState\":\"Started\","
                    + "\"newScenarioState\":\"recovered\","
                    + "\"request\":{\"method\":\"POST\",\"url\":\"/hrms/payroll\"},"
                    + "\"response\":{\"status\":503}}");
            addWireMockMapping(client, target, "{"
                    + "\"scenarioName\":\"bounded-retry\","
                    + "\"requiredScenarioState\":\"recovered\","
                    + "\"request\":{\"method\":\"POST\",\"url\":\"/hrms/payroll\"},"
                    + "\"response\":{\"status\":200,\"body\":\"accepted\"}}");
            return;
        }

        int status = mode == WireMockMode.FAILURE ? 503 : 200;
        String delay = mode == WireMockMode.DELAYED
                ? ",\"fixedDelayMilliseconds\":2000" : "";
        addWireMockMapping(client, target, "{"
                + "\"request\":{\"method\":\"POST\",\"url\":\"/hrms/payroll\","
                + "\"headers\":{"
                + "\"X-Api-Key\":{\"equalTo\":\"runtime-certification-secret\"},"
                + "\"Idempotency-Key\":{\"matches\":\"payroll-.+\"}}},"
                + "\"response\":{\"status\":" + status + ",\"body\":\"accepted\""
                + delay + "}}");
    }

    private static void addWireMockMapping(
            HttpClient client,
            RuntimeTarget target,
            String body
    ) throws Exception {
        HttpRequest mapping = HttpRequest.newBuilder(
                        URI.create(target.wiremockUrl() + "/__admin/mappings"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> mappingResponse =
                client.send(mapping, HttpResponse.BodyHandlers.ofString());
        require(mappingResponse.statusCode() >= 200 && mappingResponse.statusCode() < 300,
                "WireMock provider contract could not be configured");
    }

    private static int wireMockRequestCount(RuntimeTarget target) throws Exception {
        String body = "{\"method\":\"POST\",\"url\":\"/hrms/payroll\"}";
        HttpRequest countRequest = HttpRequest.newBuilder(
                        URI.create(target.wiremockUrl() + "/__admin/requests/count"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                countRequest, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() >= 200 && response.statusCode() < 300,
                "WireMock request count could not be read");
        var matcher = java.util.regex.Pattern
                .compile("\"count\"\\s*:\\s*(\\d+)")
                .matcher(response.body());
        require(matcher.find(), "WireMock request count response was malformed");
        return Integer.parseInt(matcher.group(1));
    }

    enum WireMockMode {
        SUCCESS,
        DELAYED,
        TRANSIENT_FAILURE,
        FAILURE
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?)",
                Boolean.class,
                table);
        return Boolean.TRUE.equals(exists);
    }

    private static long sentCount(JdbcTemplate jdbc, String table) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = 'SENT'", Long.class);
        return value == null ? 0 : value;
    }

    private static String status(JdbcTemplate jdbc, String table, String id) {
        return jdbc.queryForObject(
                "SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private static String statusOrNull(JdbcTemplate jdbc, String table, String id) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM " + table + " WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getString(1),
                id);
        return statuses.size() == 1 ? statuses.get(0) : null;
    }

    private static long countStatus(JdbcTemplate jdbc, String table, String status) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = ?",
                Long.class,
                status);
        return value == null ? 0L : value;
    }

    private static long effectCount(JdbcTemplate jdbc, String payload) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + HANDLER_EFFECT + " WHERE payload = ?",
                Long.class,
                payload);
        return value == null ? 0L : value;
    }

    private static String retainedPayload(JdbcTemplate jdbc, String table, String id) {
        List<String> payloads = jdbc.query(
                "SELECT payload FROM " + table + " WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getString(1),
                id);
        return payloads.size() == 1 ? payloads.get(0) : null;
    }

    private static int attempts(JdbcTemplate jdbc, String table, String id) {
        Integer value = jdbc.queryForObject(
                "SELECT attempts FROM " + table + " WHERE id = ?", Integer.class, id);
        return value == null ? -1 : value;
    }

    private static String lastError(JdbcTemplate jdbc, String table, String id) {
        String value = jdbc.queryForObject(
                "SELECT last_error FROM " + table + " WHERE id = ?", String.class, id);
        return value == null ? "" : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireThrowsIllegalArgument(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }

    private static void requireThrowsRuntime(Runnable operation, String message) {
        try {
            operation.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new IllegalStateException(message);
    }

    static final class VersionedObjectMapperFactoryBean implements FactoryBean<Object> {
        private final Class<?> mapperType = resolveMapperType();
        private final Object mapper = createMapper();

        @Override
        public Object getObject() {
            return mapper;
        }

        @Override
        public Class<?> getObjectType() {
            return mapperType;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        private Object createMapper() {
            try {
                return mapperType.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Could not create " + mapperType.getName(), exception);
            }
        }

        private static Class<?> resolveMapperType() {
            try {
                return Class.forName("tools.jackson.databind.ObjectMapper");
            } catch (ClassNotFoundException ignored) {
                try {
                    return Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException("No supported Jackson ObjectMapper is present",
                            exception);
                }
            }
        }
    }

    record RuntimeTarget(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String kafkaBootstrap,
            String rabbitHost,
            int rabbitPort,
            String toxiproxyUrl,
            String sftpHost,
            int sftpPort,
            String sftpUsername,
            String sftpPassword,
            String wiremockUrl,
            Path evidenceFile,
            String cellId
    ) {
        static RuntimeTarget fromEnvironment() {
            return new RuntimeTarget(
                    required("CERT_DB_URL"),
                    required("CERT_DB_USERNAME"),
                    required("CERT_DB_PASSWORD"),
                    required("CERT_KAFKA_BOOTSTRAP"),
                    required("CERT_RABBIT_HOST"),
                    Integer.parseInt(required("CERT_RABBIT_PORT")),
                    required("CERT_TOXIPROXY_URL"),
                    required("CERT_SFTP_HOST"),
                    Integer.parseInt(required("CERT_SFTP_PORT")),
                    required("CERT_SFTP_USERNAME"),
                    required("CERT_SFTP_PASSWORD"),
                    required("CERT_WIREMOCK_URL"),
                    Path.of(required("CERT_EVIDENCE_FILE")).toAbsolutePath().normalize(),
                    required("CERT_CELL_ID")
            );
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }
    }

    record InboundCertification(
            int kafkaScenarios,
            int rabbitScenarios,
            boolean missingIdentityQuarantined,
            boolean conflictingIdentityRejected,
            boolean transactionalEffectsCertified
    ) {
    }

    record CertificationEvidence(
            String cellId,
            String jmixVersion,
            int runtimeJava,
            long durationMs,
            int baselineKafkaRecords,
            int baselineRabbitMessages,
            boolean springProxyCompatible,
            boolean migrationsApplied,
            boolean brokerOutageRecovered,
            boolean strictOrderingCertified,
            boolean replayCertified,
            boolean checksumAndLeaseCertified,
            int outageKafkaDeliveries,
            boolean stableIdempotencyIdCertified,
            boolean sftpAtomicTransferCertified,
            boolean httpProviderContractCertified,
            double kafkaDeliveredMetric,
            int inboundKafkaScenarios,
            int inboundRabbitScenarios,
            boolean missingIdentityQuarantined,
            boolean conflictingIdentityRejected,
            boolean transactionalEffectsCertified
    ) {
        void write(Path file) throws Exception {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson() + "\n", StandardCharsets.UTF_8);
        }

        String toJson() {
            return "{"
                    + "\"schemaVersion\":\"integration-runtime-certification-v3\","
                    + "\"cellId\":\"" + escape(cellId) + "\","
                    + "\"jmixVersion\":\"" + escape(jmixVersion) + "\","
                    + "\"runtimeJava\":" + runtimeJava + ","
                    + "\"durationMs\":" + durationMs + ","
                    + "\"baselineKafkaRecords\":" + baselineKafkaRecords + ","
                    + "\"baselineRabbitMessages\":" + baselineRabbitMessages + ","
                    + "\"springProxyCompatible\":" + springProxyCompatible + ","
                    + "\"migrationsApplied\":" + migrationsApplied + ","
                    + "\"brokerOutageRecovered\":" + brokerOutageRecovered + ","
                    + "\"strictOrderingCertified\":" + strictOrderingCertified + ","
                    + "\"replayCertified\":" + replayCertified + ","
                    + "\"checksumAndLeaseCertified\":" + checksumAndLeaseCertified + ","
                    + "\"outageKafkaDeliveries\":" + outageKafkaDeliveries + ","
                    + "\"stableIdempotencyIdCertified\":"
                    + stableIdempotencyIdCertified + ","
                    + "\"sftpAtomicTransferCertified\":"
                    + sftpAtomicTransferCertified + ","
                    + "\"httpProviderContractCertified\":"
                    + httpProviderContractCertified + ","
                    + "\"kafkaDeliveredMetric\":" + kafkaDeliveredMetric + ","
                    + "\"inboundKafkaScenarios\":" + inboundKafkaScenarios + ","
                    + "\"inboundRabbitScenarios\":" + inboundRabbitScenarios + ","
                    + "\"missingIdentityQuarantined\":"
                    + missingIdentityQuarantined + ","
                    + "\"conflictingIdentityRejected\":"
                    + conflictingIdentityRejected + ","
                    + "\"transactionalEffectsCertified\":"
                    + transactionalEffectsCertified
                    + "}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
