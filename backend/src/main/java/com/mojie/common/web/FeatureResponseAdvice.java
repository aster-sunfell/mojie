package com.mojie.common.web;

import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(basePackages = {"com.mojie.conversation.api", "com.mojie.skill.api"})
public final class FeatureResponseAdvice implements ResponseBodyAdvice<Object> {
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    return !method.getContainingClass().equals(ApiExceptionHandler.class);
  }

  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    return body instanceof byte[] || body == null ? body : Map.of("data", body);
  }
}
