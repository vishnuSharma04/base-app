package io.intelliflow.custom;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import org.kie.kogito.internal.process.runtime.KogitoWorkItem;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemManager;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class DocTemplate implements KogitoWorkItemHandler {

    private final ObjectMapper objectMapper;
    private String templateURL;
    private String workspace;
    

    public DocTemplate() {
        objectMapper = new ObjectMapper();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application.properties");

        Properties appProps = new Properties();
        try {
            appProps.load(inputStream);
            templateURL = appProps.get("app.urls.templateURL").toString(); 
            workspace = appProps.get("ifs.app.workspace").toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {
        try {
            String TemplateName = (String) workItem.getParameter("TemplateName");
            Object body = workItem.getParameter("Data");

            if (TemplateName == null || TemplateName.trim().isEmpty()) {
                throw new IllegalArgumentException("Method is a required parameter");
            }
            String apiUrl = templateURL + "template/generate/Document/" + TemplateName + "/cds";
            Log.info("API URL: " + apiUrl);
            String bodyStr = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(new URI(apiUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json").header("Workspace", workspace)
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Log.info("Response: " + response.body());
            Log.info("Status Code: " + response.statusCode());
        String responseBody = response.body();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class); 

        String docPath = (String) responseMap.get("FileName");

        Map<String, Object> results = new HashMap<>();
        results.put("Response", responseBody);
        results.put("StatusCode", response.statusCode());
        results.put("DocumentPath", docPath);  // Add extracted field to results

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

