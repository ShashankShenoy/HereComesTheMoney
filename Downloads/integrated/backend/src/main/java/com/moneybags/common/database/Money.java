package com.moneybags.common.database;
import com.moneybags.common.api.BusinessException;
import org.springframework.http.HttpStatus;
import java.math.*;
/** INR money follows Oracle NUMBER(18,2). Never use float or double. */
public final class Money {
    private Money() {}
    public static BigDecimal positive(BigDecimal value) {
        if(value==null||value.signum()<=0)throw invalid();
        try { BigDecimal scaled=value.setScale(2,RoundingMode.UNNECESSARY);if(scaled.precision()>18)throw invalid();return scaled; }
        catch(ArithmeticException e) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_AMOUNT","Amount must be positive, fit NUMBER(18,2), and have at most two decimal places"); }
}
