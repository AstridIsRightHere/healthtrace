package com.healthtrace.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Locale;

/**
 * 全局 String→Enum 绑定：去首尾空白并忽略大小写（如 variant=preview → PREVIEW）。
 * 非法取值抛 IllegalArgumentException，由 Spring 包装为 MethodArgumentTypeMismatchException，交全局异常处理统一返回。
 */
@Configuration(proxyBeanMethods = false)
public class EnumConverterConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
    }

    /** 与旧 parseEnum 等价：Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT))。 */
    static final class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
            Class<?> enumType = targetType;
            while (enumType != null && !enumType.isEnum()) {
                enumType = enumType.getSuperclass();
            }
            if (enumType == null) {
                throw new IllegalArgumentException("目标类型不是枚举: " + targetType.getName());
            }
            Class<? extends Enum> type = (Class<? extends Enum>) enumType;
            return source -> (T) Enum.valueOf(type, source.strip().toUpperCase(Locale.ROOT));
        }
    }
}
