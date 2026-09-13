package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {
    @Bean
    public Realm localRealm() {
        SimpleAccountRealm realm = new SimpleAccountRealm();
        realm.addAccount("bootstrap", "bootstrap", "system");
        return realm;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");
        // 业务 REST 接口统一启用 HTTP Basic 认证（内置账号 bootstrap/bootstrap）
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
