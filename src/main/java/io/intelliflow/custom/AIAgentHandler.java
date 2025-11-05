package io.intelliflow.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import org.kie.kogito.internal.process.runtime.KogitoWorkItem;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemManager;
import io.intelliflow.custom.config.OlingoSupport;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;



public class AIAgentHandler implements KogitoWorkItemHandler {



    private final ObjectMapper objectMapper;
    private String agentURL;
    private String workspace;
    

    public AIAgentHandler() {
        objectMapper = new ObjectMapper();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application.properties");

        Properties appProps = new Properties();
        try {
            appProps.load(inputStream);
            agentURL = appProps.get("app.urls.agentAPI").toString(); 
            workspace = appProps.get("ifs.app.workspace").toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        try {
            String agentID = (String) workItem.getParameter("AIAgent");
            HashMap<String, Object> map = new HashMap<>();
            map.put("session_id",  workItem.getParameter("session_id"));
            map.put("user_input", workItem.getParameter("user_input"));
            map.put("fileURL", workItem.getParameter("fileURL"));
            map.put("username",  workItem.getParameter("username"));
            map.put("workspace",  workspace);
            map.put("token",  workItem.getParameter("token"));


            if (agentID == null || agentID.trim().isEmpty()) {
                throw new IllegalArgumentException("Method is a required parameter");
            }
            String apiUrl = agentURL + agentID;
            Log.info("API URL: " + apiUrl);
            
            String bodyStr = objectMapper.writeValueAsString(map);
            System.out.println(apiUrl+" /// "+agentID+" ////  "+bodyStr+" //// ");
            HttpRequest request = HttpRequest.newBuilder(new URI(apiUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json").header("Workspace", workspace)
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Log.info("Response: " + response.body());
            Log.info("Status Code: " + response.statusCode());
        
        // String outputVariable = OlingoSupport.getOutputVariable(workItem,dataOperationObject.getClass().getName());
        String responseBody = response.body();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class); 
        System.out.println(responseMap);
        Map<String, Object> results = new HashMap<>();
        results.put("agentResponse", responseMap);
       

        manager.completeWorkItem(workItem.getStringId(), results);

        } catch (Exception e) {
            e.printStackTrace();
            manager.completeWorkItem(workItem.getStringId(), null);
        }
    }

    @Override
    public void abortWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        // Handle abort logic if needed
    }
    
}
