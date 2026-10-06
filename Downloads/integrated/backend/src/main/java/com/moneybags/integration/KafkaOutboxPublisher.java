package com.moneybags.integration;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.producer.ProducerRecord;
import com.moneybags.common.database.BusinessRepository;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** At-least-once post-commit event delivery with a recoverable lease. Consumers must deduplicate eventId. */
@Component @ConditionalOnProperty(name="moneybags.kafka.enabled",havingValue="true")
public class KafkaOutboxPublisher {
 private final BusinessRepository db;private final KafkaTemplate<String,String> kafka;private final String owner=UUID.randomUUID().toString();private final String prefix;
 public static final Set<String> MODULES=Set.of("M05","M06","M07","M08");
 public KafkaOutboxPublisher(BusinessRepository db,KafkaTemplate<String,String> kafka,@Value("${moneybags.kafka.topic-prefix:moneybags}")String prefix){this.db=db;this.kafka=kafka;if(!prefix.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Invalid Kafka topic prefix");this.prefix=prefix;}
 @Scheduled(fixedDelayString="${moneybags.kafka.poll-ms:5000}",initialDelay=10000)
 public void publish(){
  for(String module:MODULES){String table=module+"_OUTBOX_EVENT";
   var candidates=db.rows("SELECT EVENT_ID FROM "+table+" WHERE AVAILABLE_AT<=SYSTIMESTAMP AND (PUBLISH_STATUS='PENDING' OR (PUBLISH_STATUS='CLAIMED' AND LOCKED_UNTIL<SYSTIMESTAMP)) ORDER BY OCCURRED_AT FETCH FIRST 5 ROWS ONLY");
   for(var candidate:candidates){byte[] id=(byte[])candidate.get("EVENT_ID");
    int claimed=db.jdbc().update("UPDATE "+table+" SET PUBLISH_STATUS='CLAIMED',LOCKED_BY=?,LOCKED_UNTIL=?,PUBLISH_ATTEMPTS=PUBLISH_ATTEMPTS+1 WHERE EVENT_ID=? AND (PUBLISH_STATUS='PENDING' OR (PUBLISH_STATUS='CLAIMED' AND LOCKED_UNTIL<SYSTIMESTAMP))",owner,OffsetDateTime.now().plusMinutes(1),id);
    if(claimed!=1)continue;
    var row=db.one("SELECT EVENT_TYPE,PARTITION_KEY,PAYLOAD_JSON,PUBLISH_ATTEMPTS FROM "+table+" WHERE EVENT_ID=?",id);
    try{
     ProducerRecord<String,String> record=new ProducerRecord<>(prefix+"."+module.toLowerCase(Locale.ROOT),(String)row.get("PARTITION_KEY"),(String)row.get("PAYLOAD_JSON"));
     record.headers().add("eventId",HexFormat.of().formatHex(id).getBytes(StandardCharsets.UTF_8));record.headers().add("eventType",((String)row.get("EVENT_TYPE")).getBytes(StandardCharsets.UTF_8));
     kafka.send(record).get(5,TimeUnit.SECONDS);
     db.jdbc().update("UPDATE "+table+" SET PUBLISH_STATUS='PUBLISHED',PUBLISHED_AT=SYSTIMESTAMP,LOCKED_BY=NULL,LOCKED_UNTIL=NULL,LAST_ERROR=NULL WHERE EVENT_ID=? AND LOCKED_BY=?",id,owner);
    }catch(Exception e){
     if(e instanceof InterruptedException)Thread.currentThread().interrupt();
     int attempts=((Number)row.get("PUBLISH_ATTEMPTS")).intValue();
     db.jdbc().update("UPDATE "+table+" SET PUBLISH_STATUS=?,AVAILABLE_AT=?,LOCKED_BY=NULL,LOCKED_UNTIL=NULL,LAST_ERROR=? WHERE EVENT_ID=? AND LOCKED_BY=?",attempts>=10?"DEAD_LETTER":"PENDING",OffsetDateTime.now().plusSeconds(Math.min(300,1L<<Math.min(8,attempts))),e.getClass().getSimpleName(),id,owner);
     return; // Broker outage: back off without walking every queued event.
    }
   }
  }
 }
}
