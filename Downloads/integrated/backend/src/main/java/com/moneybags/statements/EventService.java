package com.moneybags.statements;

import com.moneybags.statements.ApiModels.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deduplicates owner events and exposes pending publication to a trusted worker. */
@Service
public class EventService {
    private final JdbcTemplate jdbc;
    public EventService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** Records a producer event once; consumers still recheck owner facts before use. */
    @Transactional public String receive(ConsumerEvent event) {
        if(!event.payloadSha256().matches("[a-fA-F0-9]{64}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_EVENT_HASH","Invalid event hash");
        List<String> prior=jdbc.queryForList("""
            SELECT EVENT_PAYLOAD_SHA256 FROM M10_STATEMENT_CONSUMER_INBOX
            WHERE SOURCE_SERVICE=? AND EVENT_ID=?
            """,String.class,event.sourceService(),event.eventId());
        if(!prior.isEmpty()) {
            if(!prior.get(0).equalsIgnoreCase(event.payloadSha256()))
                throw new ApiException(HttpStatus.CONFLICT,"EVENT_ID_CONFLICT","Event ID reused with a different payload");
            return "DUPLICATE";
        }
        jdbc.update("""
            INSERT INTO M10_STATEMENT_CONSUMER_INBOX
             (SOURCE_SERVICE,EVENT_ID,EVENT_TYPE,EVENT_PAYLOAD_SHA256,PROCESSED_AT,RESULT_CODE)
            VALUES (?,?,?,?,SYSTIMESTAMP,'PROCESSED')
            """,event.sourceService(),event.eventId(),event.eventType(),event.payloadSha256());
        return "PROCESSED";
    }
    /** Returns pending outbox messages for the deployment's reliable broker publisher. */
    public List<OutboxEvent> pending(int limit) {
        return jdbc.query("""
            SELECT EVENT_ID,EVENT_TYPE,PAYLOAD_JSON,CORRELATION_ID
            FROM M10_STATEMENT_OUTBOX_EVENT WHERE PUBLISH_STATUS='PENDING'
            ORDER BY OCCURRED_AT FETCH FIRST ? ROWS ONLY
            """,(rs,n)->new OutboxEvent(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),
            Math.min(Math.max(limit,1),100));
    }
    /** Marks a broker-acknowledged outbox event published. */
    @Transactional public void published(String id) {
        int count=jdbc.update("""
            UPDATE M10_STATEMENT_OUTBOX_EVENT SET PUBLISH_STATUS='PUBLISHED',
                PUBLISHED_AT=SYSTIMESTAMP,PUBLISH_ATTEMPTS=PUBLISH_ATTEMPTS+1
            WHERE EVENT_ID=? AND PUBLISH_STATUS='PENDING'
            """,id);
        if(count==0) throw new ApiException(HttpStatus.CONFLICT,"EVENT_NOT_PENDING","Event is not pending");
    }
}
