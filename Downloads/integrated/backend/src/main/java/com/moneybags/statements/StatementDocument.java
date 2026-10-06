package com.moneybags.statements;

import com.moneybags.statements.ApiModels.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Deterministic documents from already-masked, immutable statement lines; no live ledger query. */
public final class StatementDocument {
 private StatementDocument(){}
 public record Document(String type,byte[] bytes){}
 public static Document render(StatementView s,String format){
  return switch(format){
   case "CSV"->new Document("text/csv;charset=UTF-8",csv(s).getBytes(StandardCharsets.UTF_8));
   case "HTML"->new Document("text/html;charset=UTF-8",html(s).getBytes(StandardCharsets.UTF_8));
   case "PDF"->new Document("application/pdf",pdf(s));
   default->throw new ApiException(HttpStatus.BAD_REQUEST,"FORMAT_INVALID","Choose PDF, CSV or HTML");
  };
 }
 private static String csvCell(Object value){String v=Objects.toString(value,"");if(!(value instanceof Number)&&v.matches("(?s)^[=+@\\t\\r\\n-].*"))v="'"+v;return "\""+v.replace("\"","\"\"")+"\"";}
 private static String csv(StatementView s){
  StringBuilder out=new StringBuilder("\uFEFFStatement,Account,Period start,Period end,Currency,Opening balance,Closing balance\r\n");
  out.append(String.join(",",Arrays.asList(s.statementNumber(),s.accountId(),s.periodStart(),s.periodEnd(),s.currency(),s.openingBalance(),s.closingBalance()).stream().map(StatementDocument::csvCell).toList())).append("\r\nLine,Value date,Booking date,Description,Amount,Running balance\r\n");
  for(var l:s.lines())out.append(String.join(",",Arrays.asList(l.lineNo(),l.valueDate(),l.bookedAt(),l.narration(),l.amount(),l.balanceAfter()).stream().map(StatementDocument::csvCell).toList())).append("\r\n");
  return out.toString();
 }
 private static String escape(Object v){return Objects.toString(v,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
 private static String html(StatementView s){
  var b=new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Money Bags statement</title><style>body{font:14px sans-serif;max-width:1000px;margin:40px auto;color:#16313a}table{border-collapse:collapse;width:100%}td,th{padding:10px;text-align:left;border-bottom:1px solid #ddd}h1{color:#006e64}</style><h1>Money Bags</h1><h2>Account statement</h2><p>");
  b.append(escape(s.statementNumber())).append(" | Account ").append(s.accountId()).append(" | ").append(s.periodStart()).append(" to ").append(s.periodEnd()).append("</p><p>Currency ").append(escape(s.currency())).append(" | Opening ").append(escape(s.openingBalance())).append(" | Closing ").append(escape(s.closingBalance())).append("</p><table><tr><th>Date</th><th>Description</th><th>Amount</th><th>Running balance</th></tr>");
  for(var l:s.lines())b.append("<tr><td>").append(l.valueDate()).append("</td><td>").append(escape(l.narration())).append("</td><td>").append(escape(l.amount())).append("</td><td>").append(escape(l.balanceAfter())).append("</td></tr>");
  return b.append("</table><p>Revision ").append(s.revision()).append(". Generated from an immutable posted-ledger snapshot.</p></html>").toString();
 }
 /** Small paginated PDF writer using standard PDF Helvetica. Unicode HTML/CSV are available for every locale. */
 private static byte[] pdf(StatementView s){
  List<String> rows=new ArrayList<>();rows.add("MONEY BAGS - ACCOUNT STATEMENT");rows.add(s.statementNumber()+"   Revision "+s.revision());rows.add("Account "+s.accountId()+"   "+s.periodStart()+" to "+s.periodEnd()+"   "+s.currency());
  rows.add("Opening: "+s.openingBalance()+"    Closing: "+s.closingBalance());rows.add("");rows.add("VALUE DATE   DESCRIPTION                         AMOUNT        BALANCE");
  for(var l:s.lines()){
   if(l.narration().chars().anyMatch(c->c>126))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"UNICODE_FORMAT_REQUIRED","Use HTML or CSV for this narration locale; the built-in PDF font supports Latin text only");
   String narration=l.narration().replaceAll("[\\r\\n\\t]"," ");
   rows.add(String.format(Locale.ROOT,"%s   %-32.32s %12s %14s",l.valueDate(),narration,l.amount(),Objects.toString(l.balanceAfter(),"")));
   for(int i=32;i<narration.length();i+=76)rows.add("    "+narration.substring(i,Math.min(i+76,narration.length())));
  }
  int pages=Math.max(1,(rows.size()+43)/44);List<byte[]> objects=new ArrayList<>();objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
  StringBuilder kids=new StringBuilder();for(int p=0;p<pages;p++)kids.append(4+2*p).append(" 0 R ");
  objects.add(bytes("<< /Type /Pages /Kids ["+kids+"] /Count "+pages+" >>"));objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>"));
  for(int p=0;p<pages;p++){
   objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents "+(5+2*p)+" 0 R >>"));
   StringBuilder stream=new StringBuilder("BT /F1 9 Tf 40 790 Td 16 TL ");
   for(String row:rows.subList(p*44,Math.min((p+1)*44,rows.size())))stream.append('(').append(row.replace("\\","\\\\").replace("(","\\(").replace(")","\\)")).append(") Tj T* ");
   stream.append("(Page ").append(p+1).append(" of ").append(pages).append(") Tj ET");
   byte[] content=bytes(stream.toString());objects.add(bytes("<< /Length "+content.length+" >>\nstream\n"+stream+"\nendstream"));
  }
  ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(bytes("%PDF-1.4\n"));List<Integer> offsets=new ArrayList<>();
  for(int i=0;i<objects.size();i++){offsets.add(out.size());out.writeBytes(bytes((i+1)+" 0 obj\n"));out.writeBytes(objects.get(i));out.writeBytes(bytes("\nendobj\n"));}
  int xref=out.size();out.writeBytes(bytes("xref\n0 "+(objects.size()+1)+"\n0000000000 65535 f \n"));for(int offset:offsets)out.writeBytes(bytes(String.format(Locale.ROOT,"%010d 00000 n \n",offset)));
  out.writeBytes(bytes("trailer\n<< /Size "+(objects.size()+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n"));return out.toByteArray();
 }
 private static byte[] bytes(String s){return s.getBytes(StandardCharsets.US_ASCII);}
}
