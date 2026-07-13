package com.flowabletest.example.delegate;

import java.math.BigDecimal;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls an external fraud-scoring HTTP service. In tests, {@code fraud-check-service.base-url} is
 * injected by the starter's WireMock convention-based discovery (see {@code
 * src/test/resources/httpmocks/fraud-check-service}); in a real deployment it points at the actual
 * gateway via {@code application.yml}.
 */
@Component("fraudCheckDelegate")
public class FraudCheckDelegate implements JavaDelegate {

  private final RestClient restClient;

  public FraudCheckDelegate(
      RestClient.Builder restClientBuilder,
      @Value("${fraud-check-service.base-url}") String baseUrl) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
  }

  @Override
  public void execute(DelegateExecution execution) {
    final String customerId = (String) execution.getVariable("customerId");
    final BigDecimal totalAmount = (BigDecimal) execution.getVariable("totalAmount");

    final FraudCheckResponse response =
        restClient
            .post()
            .uri("/v1/fraud-check")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new FraudCheckRequest(customerId, totalAmount))
            .retrieve()
            .body(FraudCheckResponse.class);

    execution.setVariable("fraudApproved", response.approved());
    execution.setVariable("fraudScore", response.score());
  }

  private record FraudCheckRequest(String customerId, BigDecimal totalAmount) {}

  private record FraudCheckResponse(boolean approved, double score) {}
}
