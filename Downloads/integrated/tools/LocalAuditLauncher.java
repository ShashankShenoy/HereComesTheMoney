import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
public class LocalAuditLauncher {
 public static void main(String[] args)throws Exception{
  var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);
  launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(args.length>2?DiscoverySelectors.selectMethod(args[0],args[2]):DiscoverySelectors.selectClass(args[0])).build());
  var summary=listener.getSummary();summary.printTo(new java.io.PrintWriter(System.out));
  var failures=new ArrayList<Map<String,Object>>();for(var f:summary.getFailures()){failures.add(Map.of("test",f.getTestIdentifier().getDisplayName(),"message",Objects.toString(f.getException().getMessage(),f.getException().getClass().getName())));System.out.println(f.getTestIdentifier().getDisplayName()+": "+f.getException());}
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("isolatedLocal",true,"liveProviderCalls",false,"tests",summary.getTestsFoundCount(),"passed",summary.getTestsSucceededCount(),"failed",summary.getTestsFailedCount(),"failures",failures));
  System.exit(summary.getTestsFailedCount()>0||summary.getTestsFoundCount()==0?1:0);
 }
}
