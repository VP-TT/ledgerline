package com.ledgerline.gateway;

import com.ledgerline.common.LedgerlineProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class GatewayConfig {

  @Bean
  RestClient gatewayRestClient(RestClient.Builder builder, LedgerlineProperties props) {
    var gw = props.gateway();
    var http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(gw.connectTimeout())
        .build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(gw.readTimeout());
    return builder.baseUrl(gw.baseUrl()).requestFactory(factory).build();
  }
}
