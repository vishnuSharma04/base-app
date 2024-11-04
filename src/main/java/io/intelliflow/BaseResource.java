package io.intelliflow;

import com.jayway.jsonpath.JsonPath;
import io.intelliflow.lifecycle.BaseAppLifecycle;
import io.intelliflow.model.ApiPath;
import io.intelliflow.model.AppModel;
import io.intelliflow.utils.CustomException;
import io.intelliflow.utils.ResponseModel;
import io.intelliflow.utils.Status;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Path("/service")
public class BaseResource {

    @Inject
    @ConfigProperty(name = "ifs.app.workspace")
    String workspace;

    @Inject
    @ConfigProperty(name = "ifs.app.miniappname")
    String miniApp;

    @Inject
    @ConfigProperty(name = "ifs.app.devicesupport")
    String deviceSupport;

    @Inject
    @ConfigProperty(name = "ifs.app.displayname")
    String appDisplayName;


    @GET
    @Path("/hello")
    @Produces(MediaType.APPLICATION_JSON)
    public JSONObject hello() {

        JSONObject obj  = new JSONObject();
        obj.put("Result", "Your App is Available");
        return obj;
    }

    @GET
    @Path("/definitions")
    public ResponseModel findDefinitions(){
        ResponseModel responseModel = new ResponseModel();
        AppModel newModel = new AppModel();
        try {
            List<ApiPath> paths = new ArrayList<ApiPath>();
            newModel.setWorkspace(workspace);
            newModel.setApp(miniApp);
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            List<String> files = getFiles("bpmn", false);
            for (String file : files) {
                InputStream inputStream = getClass().getClassLoader().getResourceAsStream("bpmn/" + file);
                Document document = documentBuilder.parse(inputStream);
                NodeList tasks = document.getElementsByTagNameNS("*", "process");
                Element task = (Element) tasks.item(0);
                ApiPath newPath = new ApiPath();
                newPath.setLabel(task.getAttribute("name"));
                newPath.setPath(task.getAttribute("id"));
                paths.add(newPath);
            }
            newModel.setPaths(paths);
            newModel.setDeviceSupport(deviceSupport);
            newModel.setAppDisplayName(appDisplayName);
            responseModel.setStatus(String.valueOf(Status.OK.getCode()));
            responseModel.setData(newModel);
        }catch (ParserConfigurationException e) {
            return new ResponseModel("Parsing configuration issue",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        } catch (IOException e) {
            return new ResponseModel("File Read Write operation issue",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        } catch (SAXException e) {
            return new ResponseModel("Xml Parsing issue",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }catch(CustomException e){
            return e.getResponseModel();
        }catch (Exception e){
            return new ResponseModel("issue while getting definitions",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }
        return responseModel;
    }

    @GET
    @Path("/form/content/{taskid}")
    public ResponseModel fetchFormContent(@PathParam("taskid") String taskid){
        ResponseModel responseModel = new ResponseModel();
        try {
            if (Objects.nonNull(BaseAppLifecycle.workFlowJson)) {
                String jsonPathExpression = "$.configuration.mapping[?(@.taskid=='" + taskid + "')].formname";
                JSONArray jsonNode = JsonPath.parse(BaseAppLifecycle.workFlowJson).read(jsonPathExpression);
                if(jsonNode.size() == 0){
                    return new ResponseModel("No Form Found",null,String.valueOf(Status.NOT_FOUND.getCode()));
                }
                String response = IOUtils.toString(
                        getClass().getClassLoader().getResourceAsStream("form/" + jsonNode.get(0)),
                        StandardCharsets.UTF_8
                );
                if (!response.isEmpty()) {
                    return new ResponseModel("Data Found",response,String.valueOf(Status.OK.getCode()));
                } else {
                    return new ResponseModel("No Data Found",null,String.valueOf(Status.NO_CONTENT.getCode()));
                }
            } else {
                return new ResponseModel("No Mapping Found",null,String.valueOf(Status.NO_CONTENT.getCode()));
            }
        }catch (IOException e){
            return new ResponseModel("File read/write issue",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }catch (Exception e){
            e.printStackTrace();
            return new ResponseModel("Form Not Found",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }
    }

    @GET
    @Path("/form/mapping")
    public ResponseModel getMapping() {
        ResponseModel responseModel = new ResponseModel();
        try {
            if (Objects.nonNull(BaseAppLifecycle.workFlowJson)) {
                String jsonPathExpression = "$.configuration.mapping";
                Object read = JsonPath.parse(BaseAppLifecycle.workFlowJson).read(jsonPathExpression);
                if(read!=null){
                    return new ResponseModel("Data Found",read,String.valueOf(Status.OK.getCode()));
                }else{
                    return new ResponseModel("No Data Found",null,String.valueOf(Status.NO_CONTENT.getCode()));
                }
            }else{
                return new ResponseModel("Workflow json is null",null,String.valueOf(Status.NO_CONTENT.getCode()));
            }
        }catch (Exception e){
            return new ResponseModel("Form Mapping failed",null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }
    }

    @GET
    @Path("/page/content/{roleid}")
    public ResponseModel fetchPageContent(@PathParam("roleid") String roleid) {
        ResponseModel responseModel = new ResponseModel();
        if(Objects.nonNull(BaseAppLifecycle.workFlowJson)){
            String jsonPathExpression = "$.configuration.rolemapping[?(@.roleid=='" + roleid + "')].page";
            JSONArray jsonNode = JsonPath.parse(BaseAppLifecycle.workFlowJson).read(jsonPathExpression);
            try {
                if(jsonNode.size() == 1) {
                    String response = IOUtils.toString(
                            getClass().getClassLoader().getResourceAsStream("page/" + jsonNode.get(0)),
                            StandardCharsets.UTF_8
                    );
                    if(!response.isEmpty()){
                        return new ResponseModel("Data Found",response,String.valueOf(Status.OK.getCode()));
                    }else{
                        return new ResponseModel("No Data Found",null,String.valueOf(Status.NO_CONTENT.getCode()));
                    }

                }else{
                    return new ResponseModel("No Json found",null,String.valueOf(Status.NO_CONTENT.getCode()));
                }
            } catch (Exception e) {
                e.printStackTrace();
                return new ResponseModel("page content not found for role id : "+roleid,null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
            }
        }else{
            return new ResponseModel("Workflow json is null",null,String.valueOf(Status.NO_CONTENT.getCode()));
        }
    }

    @GET
    @Path("/page/content/id/{pageId}")
    public ResponseModel fetchPageContentWithouRole(@PathParam("pageId") String pageId) {
        ResponseModel responseModel = new ResponseModel();
  
            try {
 
                    String response = IOUtils.toString(
                            getClass().getClassLoader().getResourceAsStream("page/" + pageId),
                            StandardCharsets.UTF_8
                    );
                    if(!response.isEmpty()){
                        return new ResponseModel("Data Found",response,String.valueOf(Status.OK.getCode()));
                    }else{
                        return new ResponseModel("No Data Found",null,String.valueOf(Status.NO_CONTENT.getCode()));
                    }


            } catch (Exception e) {
                e.printStackTrace();
                return new ResponseModel("page content not found for role id : "+roleid,null,String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
            }

    }

    public List<String> getFiles(String fileType, boolean ifMeta) throws CustomException {
        List<String> files = new ArrayList<String>();
        String filePath = null;
        if(ifMeta) {
            filePath = "meta/" + fileType;
        } else {
            filePath = fileType;
        }
        try {
            URI uri = getClass().getClassLoader().getResource(filePath).toURI();
            java.nio.file.Path myPath;
            if (uri.getScheme().equals("jar")) {
                FileSystem fileSystem ;
                try {
                    fileSystem = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
                } catch (FileSystemAlreadyExistsException e) {
                    fileSystem = FileSystems.getFileSystem(uri);
                }
                myPath = fileSystem.getPath(filePath);
            } else {
                myPath = Paths.get(uri);
            }
            Stream<java.nio.file.Path> walk = Files.walk(myPath, 1);
            for (Iterator<java.nio.file.Path> it = walk.iterator(); it.hasNext();){
                String fileName = it.next().getFileName().toString();
                if(fileName.indexOf("." + fileType) != -1){
                    files.add(fileName);
                }
                if(ifMeta && fileName.indexOf(".meta") != -1){
                    files.add(fileName);
                }
            }
        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new CustomException("a string could not be parsed as a URI reference",String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        } catch (IOException e) {
            e.printStackTrace();
            throw new CustomException("file I/O issue",String.valueOf(Status.INTERNAL_SERVER_ERROR.getCode()));
        }
        return files;
    }
}
