package com.dartwatch.collection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CollectionProperties.class)
public class CollectionConfiguration { }
