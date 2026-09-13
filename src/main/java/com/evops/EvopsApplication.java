package com.evops;

import org.apache.shiro.spring.boot.autoconfigure.ShiroAnnotationProcessorAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 项目通过 Shiro filter chain 做鉴权，不使用 @RequiresPermissions 等注解；
// 排除注解处理器自动配置，避免其 JDK 动态代理与事务代理叠加导致具体类注入失败。
@SpringBootApplication(exclude = ShiroAnnotationProcessorAutoConfiguration.class)
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
