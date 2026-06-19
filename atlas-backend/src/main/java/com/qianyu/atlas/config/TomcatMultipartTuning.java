package com.qianyu.atlas.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * 放宽 Tomcat 的 multipart 文件数与参数数上限。
 *
 * 背景:Tomcat 10.1+ 对 multipart 请求有 maxParameterCount(默认 10000)
 * 和 maxPartCount 限制,大量文件投递(例如 AI 生图项目一次 60+ 张)时
 * 可能触发 "Parameter count exceeded" 被截断。
 * 这里把 connector 的 maxParameterCount / maxPartCount 调到 20000,
 * 确保批量投递不被截断。文件大小上限仍走 spring.servlet.multipart。
 */
@Configuration
public class TomcatMultipartTuning implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final int MAX_PARAMETER_COUNT = 20000;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers((Connector connector) -> {
            connector.setProperty("maxParameterCount", String.valueOf(MAX_PARAMETER_COUNT));
            connector.setProperty("maxPartCount", String.valueOf(MAX_PARAMETER_COUNT));
        });
    }
}
