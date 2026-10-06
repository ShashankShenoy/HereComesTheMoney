package com.moneybags.integration;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.*;
import java.util.*;
/** Fixed-rate monthly equal-principal reducing-balance schedule. Final installment absorbs rounding. */
public final class LoanScheduleMath {
 private LoanScheduleMath(){}
 public record Installment(int number,LocalDate due,BigDecimal opening,BigDecimal principal,BigDecimal interest){}
 public static List<Installment> calculate(BigDecimal principal,BigDecimal annualRate,int months,LocalDate start,String basis){
  if(principal.signum()<=0||annualRate.signum()<0||months<1||months>600||!Set.of("ACT/365","ACT_365","ACTUAL_365","30/360").contains(basis))throw new IllegalArgumentException("Supported schedule: 1-600 months, ACT/365 or 30/360");
  List<Installment> rows=new ArrayList<>();BigDecimal outstanding=principal;BigDecimal regular=principal.divide(BigDecimal.valueOf(months),2,RoundingMode.DOWN);LocalDate previous=start;
  for(int i=1;i<=months;i++){
   LocalDate due=start.plusMonths(i);long days=basis.equals("30/360")?30:ChronoUnit.DAYS.between(previous,due);
   BigDecimal interest=outstanding.multiply(annualRate).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(basis.equals("30/360")?36000:36500),2,RoundingMode.HALF_EVEN);
   BigDecimal amount=i==months?outstanding:regular;rows.add(new Installment(i,due,outstanding,amount,interest));outstanding=outstanding.subtract(amount);previous=due;
  }
  return List.copyOf(rows);
 }
}
