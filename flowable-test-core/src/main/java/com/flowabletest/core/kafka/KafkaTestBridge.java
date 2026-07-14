package com.flowabletest.core.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Publishes to and awaits messages on Kafka topics using a {@link KafkaProducer}/{@link
 * KafkaConsumer} pair, replacing the raw producer/consumer setup that otherwise gets hand-rolled in
 * every Flowable + Kafka Event Registry test class. Operates purely on topic/key/value strings, with
 * no payload schema knowledge.
 *
 * <p>Registered as a bean by {@code FlowableTestKafkaAutoConfiguration}, pointed at whichever
 * embedded broker is active for the test.
 */
public final class KafkaTestBridge implements AutoCloseable {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

  private final String bootstrapServers;
  private final KafkaProducer<String, String> producer;

  public KafkaTestBridge(String bootstrapServers) {
    this.bootstrapServers = bootstrapServers;

    final Map<String, Object> producerProps = new HashMap<>();
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    this.producer = new KafkaProducer<>(producerProps);
  }

  public void send(String topic, String key, String value) {
    try {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to publish to topic '" + topic + "'", e);
    }
  }

  /**
   * Consumes {@code topic} from the beginning on a fresh, isolated consumer group and returns up to
   * {@code expectedCount} messages matching {@code matcher}, stopping once that many are found or
   * {@code timeout} elapses (whichever first -- a short list is returned rather than an exception,
   * since "fewer messages than expected" is itself a meaningful assertion failure for the caller to
   * report with full context).
   */
  public List<String> awaitMessages(
      String topic, Predicate<String> matcher, int expectedCount, Duration timeout) {
    final Map<String, Object> consumerProps = new HashMap<>();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "flowable-test-bridge-" + UUID.randomUUID());
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    final List<String> matched = new ArrayList<>();
    try (final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(List.of(topic));
      final Instant deadline = Instant.now().plus(timeout);
      while (matched.size() < expectedCount && Instant.now().isBefore(deadline)) {
        final ConsumerRecords<String, String> records = consumer.poll(DEFAULT_POLL_INTERVAL);
        records.forEach(
            r -> {
              if (r.value() != null && matcher.test(r.value())) {
                matched.add(r.value());
              }
            });
      }
    }
    return List.copyOf(matched);
  }

  /** Convenience for the common case of waiting on exactly one matching message. */
  public String awaitMessage(String topic, Predicate<String> matcher, Duration timeout) {
    final List<String> messages = awaitMessages(topic, matcher, 1, timeout);
    if (messages.isEmpty()) {
      throw new AssertionError(
          "Timed out after "
              + timeout
              + " waiting for a matching message on topic '"
              + topic
              + "'");
    }
    return messages.get(0);
  }

  @Override
  public void close() {
    producer.close();
  }
}
