package com.moneybags.common.database;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DbColumn {
    String name();
    boolean generated() default false;
    boolean identity() default false;
    boolean nullable() default true;
}
