package com.evops.config;

import com.evops.geothermal.security.EvopsAuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        // 多租户账号目录：t1admin / t1view / t2admin（及向后兼容的 bootstrap）
        return new EvopsAuthorizingRealm();
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");
        // 业务 REST 接口统一启用 HTTP Basic 认证
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
