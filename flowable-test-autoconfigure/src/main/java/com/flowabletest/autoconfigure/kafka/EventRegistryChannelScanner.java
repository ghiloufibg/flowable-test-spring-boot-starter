package com.flowabletest.autoconfigure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads Flowable's own Kafka Event Registry {@code *.channel} JSON descriptors (a generic
 * Flowable convention, not specific to any one consumer project) and extracts the Kafka topic
 * names they declare, so an embedded broker can be started with the right topics without the
 * consumer having to hand-maintain a topic list (design doc section 4.2).
 *
 * <p>Recognizes both shapes Flowable uses: outbound channels declare a single {@code "topic"}
 * string, inbound channels declare a {@code "topics"} array. Only descriptors with
 * {@code "type": "kafka"} are considered -- other transports (e.g. JMS, RabbitMQ) are ignored.
 */
public class EventRegistryChannelScanner {

    private final ResourcePatternResolver resourcePatternResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventRegistryChannelScanner(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public Set<String> discoverKafkaTopics(String locationPattern) {
        Set<String> topics = new LinkedHashSet<>();
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan for event registry channel descriptors at '" + locationPattern + "'", e);
        }

        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                if (root == null || !"kafka".equalsIgnoreCase(textOrNull(root, "type"))) {
                    continue;
                }

                JsonNode topic = root.get("topic");
                if (topic != null && topic.isTextual()) {
                    topics.add(topic.asText());
                }

                JsonNode topicsArray = root.get("topics");
                if (topicsArray != null && topicsArray.isArray()) {
                    topicsArray.forEach(node -> {
                        if (node.isTextual()) {
                            topics.add(node.asText());
                        }
                    });
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read event registry channel descriptor: " + resource, e);
            }
        }
        return topics;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
