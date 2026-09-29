package io.github.renhaowan.platform.gateway.forward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.registry.ToolDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 泛化 HTTP 转发：按工具注册信息把模型给的 arguments 拼装成真实 HTTP 请求。
 * <p>
 * 拼装规则：url_template 的 {param} 占位符从 arguments 取值（URL 编码）；
 * GET/DELETE/非 body 方法的其余参数拼 query string；POST/PUT/PATCH 的其余参数作为 JSON body。
 * 后端响应截断 8KB（防打爆模型上下文）；非 2xx 与异常均转为 isError 结果交给模型决策。
 */
@Component
public class GenericHttpForwarder {

    public static final int MAX_BODY_CHARS = 8192;
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");

    private final RestClient restClient;

    public GenericHttpForwarder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public record ForwardResult(boolean success, int status, String body, String error) {
    }

    public ForwardResult forward(ToolDefinition tool, JsonNode arguments) {
        try {
            Map<String, String> pathValues = extractPathValues(tool.getUrlTemplate(), arguments);
            String url = fillTemplate(tool.getUrlTemplate(), pathValues);
            JsonNode remaining = without(arguments, pathValues.keySet());

            boolean bodyAllowed = "POST".equals(tool.getHttpMethod())
                    || "PUT".equals(tool.getHttpMethod())
                    || "PATCH".equals(tool.getHttpMethod());
            if (!bodyAllowed && remaining != null && remaining.size() > 0) {
                url = appendQuery(url, remaining);
            }

            RestClient.RequestBodySpec spec = restClient
                    .method(HttpMethod.valueOf(tool.getHttpMethod()))
                    .uri(URI.create(url))
                    .accept(MediaType.APPLICATION_JSON);
            if (bodyAllowed && remaining != null && remaining.size() > 0) {
                spec.contentType(MediaType.APPLICATION_JSON)
                        .body(remaining.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (tool.getAuthHeaderName() != null && !tool.getAuthHeaderName().isBlank()) {
                spec.header(tool.getAuthHeaderName(), tool.getAuthHeaderValue() == null ? "" : tool.getAuthHeaderValue());
            }

            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean success = status >= 200 && status < 300;
                return new ForwardResult(success, status, truncate(body),
                        success ? null : "backend responded " + status);
            });
        } catch (Exception e) {
            return new ForwardResult(false, 0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, String> extractPathValues(String urlTemplate, JsonNode arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = PATH_PARAM.matcher(urlTemplate);
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode value = arguments == null ? null : arguments.get(name);
            values.put(name, value == null ? "" : urlEncode(value.asText()));
        }
        return values;
    }

    private String fillTemplate(String template, Map<String, String> values) {
        Matcher matcher = PATH_PARAM.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private JsonNode without(JsonNode arguments, Iterable<String> names) {
        if (arguments == null || !arguments.isObject()) {
            return null;
        }
        ObjectNode copy = ((ObjectNode) arguments).deepCopy();
        for (String name : names) {
            copy.remove(name);
        }
        return copy;
    }

    private String appendQuery(String url, JsonNode params) {
        StringBuilder sb = new StringBuilder(url);
        boolean first = !url.contains("?");
        for (Map.Entry<String, JsonNode> entry : params.properties()) {
            sb.append(first ? '?' : '&')
                    .append(urlEncode(entry.getKey())).append('=')
                    .append(urlEncode(entry.getValue().asText()));
            first = false;
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncate(String body) {
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }
}
