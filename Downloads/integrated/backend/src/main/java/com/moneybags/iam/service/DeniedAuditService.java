package com.moneybags.iam.service;
import com.moneybags.common.api.Correlation;
import com.moneybags.common.database.*;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.UUID;
import static com.moneybags.iam.repository.IamRepository.map;
@Service
public class DeniedAuditService {
    private final SchemaRepository schema;private final Clock clock;
    public DeniedAuditService(SchemaRepository schema,Clock clock){this.schema=schema;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(UserPrincipal actor,String path,int status){
        schema.insert(SchemaTable.M01_IAM_AUDIT_EVENT,map("AUDIT_ID",UUID.randomUUID().toString(),"EVENT_TYPE","IAM_REQUEST_REJECTED","ACTOR_USER_ID",actor.userId(),"SESSION_ID",actor.sessionId(),"RESOURCE_TYPE","IAM_API","RESOURCE_ID",path.substring(0,Math.min(80,path.length())),"RESULT",status==401||status==403?"DENIED":"FAILED","REASON_CODE","HTTP_"+status,"CORRELATION_ID",Correlation.current(),"OCCURRED_AT",OffsetDateTime.now(clock)));
    }
}
