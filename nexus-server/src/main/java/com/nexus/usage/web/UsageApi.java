package com.nexus.usage.web;

import com.nexus.usage.ApiCode;
import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UsageApi {
    ApiCode value();
}