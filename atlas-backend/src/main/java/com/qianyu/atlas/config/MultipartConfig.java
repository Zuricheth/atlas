package com.qianyu.atlas.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat Connector 级别的 multipart 配置。
 *
 * 背景：Tomcat 10.1 / Spring Boot 3.5 下，spring.servlet.multipart.max-file-count
 * 这个 yaml 属性对「单个 multipart 请求里 part 总数（含表单字段）」的透传不可靠——
 * Spring 的 MultipartConfigFactory 没有 setPartCount 方法，标准 MultipartConfigElement
 * 也没有 fileCountMax 字段。Tomcat 默认 part 数上限较低，
 * 导致图片库批量投送（一次几十上百个 files part）触发 FileCountLimitExceededException。
 *
 * 正确入口：org.apache.catalina.connector.Connector.setMaxPartCount(int)。
 * 用 TomcatConnectorCustomizer 直接拿到 Connector 实例强转调用 setter，确保生效。
 */
@Configuration
public class MultipartConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatPartCountCustomizer() {
        return factory -> factory.addConnectorCustomizers((TomcatConnectorCustomizer) connector -> {
            // connector 就是 org.apache.catalina.connector.Connector，直接调 setter
            connector.setMaxPartCount(1000);
        });
    }
}
