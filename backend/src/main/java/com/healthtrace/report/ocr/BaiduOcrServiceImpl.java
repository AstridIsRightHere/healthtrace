package com.healthtrace.report.ocr;

import com.healthtrace.report.ocr.OcrModels.OcrInput;
import com.healthtrace.report.ocr.OcrModels.OcrItem;
import com.healthtrace.report.ocr.OcrModels.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 百度智能云“医疗检验报告单识别”（ADR-004）。
 * <ul>
 *   <li>鉴权：client_credentials 换取 access_token，进程内缓存，提前 1 天刷新；token 失效错误时刷新并重试一次。</li>
 *   <li>请求：图片以 Base64 + URL 编码放入 {@code image}；PDF 用 {@code pdf_file}，当前只识别第 1 页。</li>
 *   <li>安全：凭据与 access_token 只出现在请求中，不写日志、不进异常消息；日志只记录 photoId 与错误码。</li>
 * </ul>
 */
@Service
public class BaiduOcrServiceImpl implements OcrService {

    public static final String PROVIDER = "baidu-medical-report";

    private static final Logger log = LoggerFactory.getLogger(BaiduOcrServiceImpl.class);

    /** 百度限制：Base64 编码后不超过 10 MB。 */
    static final long MAX_BASE64_BYTES = 10L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** DEC-009：单页草稿 30 秒内，单次请求留 20 秒。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofDays(1);

    /** access_token 无效或过期。 */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(110, 111);
    /** 鉴权/权限类：凭据错误、未开通服务。 */
    private static final Set<Integer> AUTH_ERRORS = Set.of(6, 14, 100);
    /** 限流、配额、服务内部错误：可重试。 */
    private static final Set<Integer> RETRYABLE_ERRORS = Set.of(1, 2, 4, 17, 18, 19, 282000);

    private final OcrProperties.Baidu config;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    private volatile CachedToken token;

    @Autowired
    public BaiduOcrServiceImpl(OcrProperties properties, JsonMapper jsonMapper, Clock clock) {
        this(properties, jsonMapper, clock,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    BaiduOcrServiceImpl(OcrProperties properties, JsonMapper jsonMapper, Clock clock, HttpClient httpClient) {
        this.config = properties.baidu();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public OcrResult recognize(List<OcrInput> pages) throws OcrException {
        if (pages == null || pages.isEmpty()) {
            throw new OcrException(OcrException.UNRECOGNIZABLE, false, "没有可识别的文件");
        }
        List<OcrItem> items = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        ArrayNode rawPages = jsonMapper.createArrayNode();
        LocalDate examinationDate = null;
        String requestIds = null;

        for (OcrInput page : pages) {
            JsonNode response = callWithTokenRetry(page);
            String logId = BaiduMedicalReportParser.textOf(response.get("log_id"));
            if (logId != null) {
                requestIds = requestIds == null ? logId : requestIds + "," + logId;
            }
            BaiduMedicalReportParser.PageResult parsed =
                    BaiduMedicalReportParser.parse(response.get("words_result"), page.photoId());
            items.addAll(parsed.items());
            if (pages.size() > 1) {
                lines.add("—— 第 " + page.pageOrder() + " 页 ——");
            }
            lines.addAll(parsed.textLines());
            if (examinationDate == null) {
                examinationDate = parsed.examinationDate();
            }
            rawPages.addObject().put("photoId", page.photoId()).set("response", response);
        }

        if (items.isEmpty()) {
            throw new OcrException(OcrException.UNRECOGNIZABLE, false, "未识别到检验项目");
        }
        if (requestIds != null && requestIds.length() > 160) {
            requestIds = requestIds.substring(0, 160);
        }
        return new OcrResult(requestIds, String.join("\n", lines), rawPages.toString(), examinationDate, items);
    }

    private JsonNode callWithTokenRetry(OcrInput page) throws OcrException {
        String form = buildForm(page);
        JsonNode response = post(form, accessToken(false));
        Integer error = errorCode(response);
        if (error != null && TOKEN_ERRORS.contains(error)) {
            response = post(form, accessToken(true));
            error = errorCode(response);
        }
        if (error != null) {
            throw mapError(error, page.photoId());
        }
        return response;
    }

    private String buildForm(OcrInput page) throws OcrException {
        String field = switch (page.mediaType()) {
            case "image/jpeg", "image/png" -> "image";
            case "application/pdf" -> "pdf_file";
            default -> throw new OcrException(OcrException.UNRECOGNIZABLE, false, "该文件格式暂不支持自动识别");
        };
        byte[] bytes;
        try (InputStream in = page.content().getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException | RuntimeException ex) {
            throw new OcrException(OcrException.SOURCE_UNAVAILABLE, true, "读取原件失败");
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        if (base64.length() > MAX_BASE64_BYTES) {
            throw new OcrException(OcrException.UNRECOGNIZABLE, false, "文件过大，无法自动识别");
        }
        String form = field + "=" + URLEncoder.encode(base64, StandardCharsets.UTF_8);
        if ("pdf_file".equals(field)) {
            form += "&pdf_file_num=1";
        }
        return form;
    }

    private JsonNode post(String form, String accessToken) throws OcrException {
        URI uri = URI.create(config.baseUrl() + "/rest/2.0/ocr/v1/medical_report_detection?access_token="
                + URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return send(request);
    }

    private String accessToken(boolean forceRefresh) throws OcrException {
        CachedToken current = token;
        Instant now = clock.instant();
        if (!forceRefresh && current != null && now.isBefore(current.refreshAt())) {
            return current.value();
        }
        synchronized (this) {
            current = token;
            if (!forceRefresh && current != null && now.isBefore(current.refreshAt())) {
                return current.value();
            }
            String form = "grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(config.secretKey(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/oauth/2.0/token"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            JsonNode body = send(request);
            String value = BaiduMedicalReportParser.textOf(body.get("access_token"));
            if (value == null) {
                // 不记录响应内容：其中可能回显 client_id
                log.warn("百度 OCR 获取 access_token 失败");
                throw new OcrException(OcrException.AUTH_FAILED, false, "OCR 服务鉴权失败");
            }
            long expiresIn = body.path("expires_in").asLong(0);
            Duration lifetime = Duration.ofSeconds(Math.max(expiresIn, 0));
            Duration usable = lifetime.compareTo(TOKEN_REFRESH_MARGIN.multipliedBy(2)) > 0
                    ? lifetime.minus(TOKEN_REFRESH_MARGIN) : lifetime.dividedBy(2);
            token = new CachedToken(value, now.plus(usable));
            return value;
        }
    }

    private JsonNode send(HttpRequest request) throws OcrException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new OcrException(OcrException.UNAVAILABLE, true, "OCR 服务连接失败");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.UNAVAILABLE, true, "OCR 调用被中断");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new OcrException(OcrException.AUTH_FAILED, false, "OCR 服务鉴权失败");
        }
        if (status == 429 || status >= 500) {
            throw new OcrException(OcrException.UNAVAILABLE, true, "OCR 服务暂不可用");
        }
        try {
            JsonNode body = jsonMapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new OcrException(OcrException.UNAVAILABLE, true, "OCR 服务返回格式异常");
            }
            if (status >= 400 && errorCode(body) == null && body.get("access_token") == null) {
                throw new OcrException(status == 400 || status == 404 ? OcrException.AUTH_FAILED
                        : OcrException.UNAVAILABLE, status != 400 && status != 404, "OCR 服务请求失败");
            }
            return body;
        } catch (JacksonException ex) {
            throw new OcrException(OcrException.UNAVAILABLE, true, "OCR 服务返回格式异常");
        }
    }

    static Integer errorCode(JsonNode body) {
        JsonNode code = body.get("error_code");
        if (code == null || code.isNull()) {
            // token 接口错误格式：{"error":"invalid_client","error_description":"..."}
            return body.get("error") != null && body.get("access_token") == null ? 100 : null;
        }
        return code.asInt(-1);
    }

    private static OcrException mapError(int code, long photoId) {
        log.warn("百度 OCR 识别失败：photoId={}, error_code={}", photoId, code);
        if (TOKEN_ERRORS.contains(code) || AUTH_ERRORS.contains(code)) {
            return new OcrException(OcrException.AUTH_FAILED, false, "OCR 服务鉴权失败");
        }
        if (RETRYABLE_ERRORS.contains(code)) {
            return new OcrException(OcrException.UNAVAILABLE, true, "OCR 服务暂不可用");
        }
        return new OcrException(OcrException.UNRECOGNIZABLE, false, "图片无法识别，可手动录入");
    }

    private record CachedToken(String value, Instant refreshAt) {
        @Override
        public String toString() {
            return "CachedToken[***]";
        }
    }
}
