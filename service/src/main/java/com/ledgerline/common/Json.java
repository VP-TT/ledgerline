package com.ledgerline.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) { this.mapper = mapper; }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public ObjectMapper mapper() { return mapper; }
}
