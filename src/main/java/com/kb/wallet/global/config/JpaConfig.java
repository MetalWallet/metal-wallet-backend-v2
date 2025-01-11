package com.kb.wallet.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class JpaConfig {

  @Configuration
  @Profile("dev")
  @PropertySource("classpath:application-dev.properties")
  public static class DevJpaConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Bean
    public Properties jpaProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.hbm2ddl.auto", "update");
      properties.put("hibernate.dialect", "org.hibernate.dialect.MySQL5InnoDBDialect");
      properties.put("hibernate.show_sql", "true");
      properties.put("hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      return properties;
    }
  }

  @Configuration
  @Profile("prod")
  @PropertySource("classpath:application-prod.properties")
  public static class ProdJpaConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Bean
    public Properties jpaProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.hbm2ddl.auto", "none");
      properties.put("hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect");
      properties.put("hibernate.show_sql", "false");
      properties.put("hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      return properties;
    }
  }

  @Configuration
  @Profile("test")
  @PropertySource("classpath:application-test.properties")
  public static class TestJpaConfig {
    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Bean
    public Properties jpaProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.hbm2ddl.auto", "update");
      properties.put("hibernate.dialect", "org.hibernate.dialect.MySQL5InnoDBDialect");
      properties.put("hibernate.show_sql", "true");
      properties.put("hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
      return properties;
    }
  }
}
