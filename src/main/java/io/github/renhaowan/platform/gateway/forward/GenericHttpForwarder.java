package io.github.renhaowan.platform.gateway.forward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
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
 * <p>
 * 传输层按三态处理：成功、明确失败（请求未送达或后端明确拒绝）、结果未知
 * （读超时/连接建立后中断，请求可能已执行）。未知态对写操作提示先查询确认，
 * 避免模型把"响应丢了"当成"没执行"而直接重试。
 */
@Component
public class GenericHttpForwarder {

    public static final int MAX_BODY_CHARS = 8192;
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");

    /**
     * 以系统凭证调用业务系统时，声明"我代表哪个最终用户"的透传头。
     * <p>
     * 业务系统只在服务级凭证（{@code X-Service-Key}）校验通过时才采信该头，因此它不构成
     * 越权入口。接收端实现在业务系统仓库（meeting-booking 的 {@code JwtFilter}），
     * 跨仓库无法共享常量，改动本常量时需两边同步。
     */
    public static final String ON_BEHALF_OF_HEADER = "X-On-Behalf-Of-User";

    private final RestClient restClient;

    public GenericHttpForwarder(ForwardProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public record ForwardResult(boolean success, int status, String body, String error, boolean unknown) {
        /** 明确结果（成功或确定失败），用于后端给出非 2xx 响应的场景。 */
        public ForwardResult(boolean success, int status, String body, String error) {
            this(success, status, body, error, false);
        }
    }

    /**
     * @param onBehalfOfUserId 最终用户 id，用于业务系统按人归属数据；null 表示不带（无用户上下文）
     */
    public ForwardResult forward(ToolDefinition tool, JsonNode arguments, Long onBehalfOfUserId) {
        try {
            Map<String, String> pathValues = extractPathValues(tool.getUrlTemplate(), arguments);
            String url = fillTemplate(tool.getUrlTemplate(), pathValues);
            JsonNode remaining = without(arguments, pathValues.keySet());

            // 导入规则将 requestBody 包在 inputSchema.properties.body 下：
            // 转发时自动解包——body 属性作为 HTTP 请求体，其余顶层参数按方法拼 query
            boolean hasBodyWrapper = hasBodyWrapper(tool) && remaining != null && remaining.hasNonNull("body");
            JsonNode httpBody = hasBodyWrapper ? remaining.get("body") : remaining;
            if (hasBodyWrapper) {
                ((ObjectNode) remaining).remove("body");
            }

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
            if (bodyAllowed && httpBody != null && httpBody.size() > 0) {
                spec.contentType(MediaType.APPLICATION_JSON)
                        .body(httpBody.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (tool.getAuthHeaderName() != null && !tool.getAuthHeaderName().isBlank()) {
                spec.header(tool.getAuthHeaderName(), tool.getAuthHeaderValue() == null ? "" : tool.getAuthHeaderValue());
            }
            if (onBehalfOfUserId != null) {
                // 不带此头时，业务系统会把所有操作记在系统账号名下，"查我的预订"就查不出真实数据
                spec.header(ON_BEHALF_OF_HEADER, String.valueOf(onBehalfOfUserId));
            }

            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                return httpResult(tool.getHttpMethod(), status, body);
            });
        } catch (Exception e) {
            return transportFailure(tool.getHttpMethod(), e);
        }
    }

    private ForwardResult httpResult(String method, int status, String body) {
        boolean success = status >= 200 && status < 300;
        String truncated = truncate(body);
        if (success) {
            return new ForwardResult(true, status, truncated, null, false);
        }
        // 502/504 来自网关或超时：上游可能已经执行，不能当成明确失败
        if (status == 502 || status == 504) {
            return new ForwardResult(false, status, truncated,
                    "结果未知（backend responded " + status + "）：" + retryGuidance(method), true);
        }
        return new ForwardResult(false, status, truncated, "backend responded " + status, false);
    }

    /**
     * 传输失败分两类：连接阶段失败可确认请求未送达；读超时与连接建立后的中断
     * 无法确认业务系统是否已经执行，必须按"结果未知"回喂。
     */
    private ForwardResult transportFailure(String method, Exception e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (isDefinitelyNotDelivered(e)) {
            return new ForwardResult(false, 0, null,
                    "请求未送达（" + reason + "），可稍后重试", false);
        }
        if (!isTransportError(e)) {
            // 请求还没发出去就失败了（URL 非法、HTTP 方法非法、序列化失败）：
            // 不存在"上游可能已执行"的可能，不能按结果未知让模型去查询。
            return new ForwardResult(false, 0, null, "调用未发出（" + reason + "）", false);
        }
        return new ForwardResult(false, 0, null,
                "结果未知（" + reason + "）：" + retryGuidance(method), true);
    }

    /** 只有 IO 链上的失败才存在"请求可能已写出"的问题；本地构造错误一律不算。 */
    private static boolean isTransportError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    private static String retryGuidance(String method) {
        return isReadOnly(method)
                ? "该操作只读，可安全重试"
                : "请求可能已在业务系统执行，不要直接重试，请先调用查询类工具核实状态";
    }

    /** 连接超时、拒绝、DNS 失败、路由不可达都发生在请求写出之前。 */
    private static boolean isDefinitelyNotDelivered(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** 判断工具的 inputSchema 是否采用 body 包装（导入规则：requestBody → properties.body）。 */
    private boolean hasBodyWrapper(ToolDefinition tool) {
        try {
            JsonNode schema = new ObjectMapper().readTree(tool.getInputSchema() == null ? "{}" : tool.getInputSchema());
            return schema.path("properties").has("body");
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> extractPathValues(String urlTemplate, JsonNode arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = PATH_PARAM.matcher(urlTemplate);
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode value = arguments == null ? null : arguments.get(name);
            values.put(name, value == null ? "" : encodePathValue(value.asText()));
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

    /** 路径段编码：URLEncoder 会把空格编成 '+'，而 path 里的 '+' 是字面量，必须用 %20。 */
    private String encodePathValue(String value) {
        return org.springframework.web.util.UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private String truncate(String body) {
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int end = MAX_BODY_CHARS;
        // 截断点落在 emoji 等代理对中间时会留下孤立的高位代理，回退一个字符
        if (Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(0, end);
    }
}
