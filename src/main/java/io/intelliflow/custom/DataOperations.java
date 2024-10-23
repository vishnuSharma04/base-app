package io.intelliflow.custom;

import com.google.gson.*;
import io.intelliflow.custom.config.OlingoSupport;
import io.quarkus.logging.Log;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.apache.olingo.client.api.ODataClient;
import org.apache.olingo.client.api.communication.ODataClientErrorException;
import org.apache.olingo.client.api.communication.request.cud.ODataDeleteRequest;
import org.apache.olingo.client.api.communication.request.cud.ODataEntityCreateRequest;
import org.apache.olingo.client.api.communication.request.cud.ODataEntityUpdateRequest;
import org.apache.olingo.client.api.communication.request.cud.UpdateType;
import org.apache.olingo.client.api.communication.request.retrieve.ODataEntitySetIteratorRequest;
import org.apache.olingo.client.api.communication.request.retrieve.ODataEntitySetRequest;
import org.apache.olingo.client.api.communication.response.ODataDeleteResponse;
import org.apache.olingo.client.api.communication.response.ODataEntityCreateResponse;
import org.apache.olingo.client.api.communication.response.ODataEntityUpdateResponse;
import org.apache.olingo.client.api.communication.response.ODataRetrieveResponse;
import org.apache.olingo.client.api.domain.*;
import org.apache.olingo.client.api.uri.QueryOption;
import org.apache.olingo.client.api.uri.URIBuilder;
import org.apache.olingo.client.core.ODataClientFactory;
import org.apache.olingo.client.core.domain.ClientCollectionValueImpl;
import org.apache.olingo.client.core.domain.ClientPropertyImpl;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.format.ContentType;
import org.kie.kogito.internal.process.runtime.KogitoWorkItem;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemHandler;
import org.kie.kogito.internal.process.runtime.KogitoWorkItemManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

public class DataOperations implements KogitoWorkItemHandler {

    Properties properties = new Properties();

    private String workspace;

    private String appName;

    private String odataUrl;

    private ODataClient client;

    private String miniAppName;

    //Supported Date Formats in Data Operations
    private static final String[] DATE_FORMATS = new String[] {
            "MMM dd, yyyy HH:mm:ss",
            "EEE MMM dd HH:mm:ss 'GMT' yyyy",
            "MMM dd, yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd-MM-yy"
    };

    public DataOperations() {
        /*
         * Default Constructor to pick values from the properties file
         */
        client = ODataClientFactory.getClient();
        client.getConfiguration().setDefaultPubFormat(ContentType.JSON_NO_METADATA);
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application.properties");
        try {
            properties.load(inputStream);
            workspace = properties.get("ifs.app.workspace").toString();
            appName = properties.get("ifs.app.miniappname").toString();
            odataUrl = "http://" + properties.get("ifs.server.url").toString() + "/query";
            inputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void executeWorkItem(KogitoWorkItem workItem, KogitoWorkItemManager manager) {

        Log.info("Data Operations Begins!!");
        Map<String, Object> results = new HashMap<>();
        String dataOperation = null;
        Object dataOperationObject = null;
        int top = Objects.nonNull(workItem.getParameter("Top")) ? (Integer) workItem.getParameter("Top") : 0;
        int skip = Objects.nonNull(workItem.getParameter("Skip")) ? (Integer) workItem.getParameter("Skip") : 0;
        String order = Objects.nonNull(workItem.getParameter("Order"))
                ? workItem.getParameter("Order").toString().toLowerCase()
                : "desc";
        String fetchParam = Objects.nonNull(workItem.getParameter("Param"))
                ? workItem.getParameter("Param").toString()
                : null;
        miniAppName = Objects.nonNull(workItem.getParameter("AppName"))
                ? workItem.getParameter("AppName").toString().toLowerCase()
                : appName;

        for (String parameter : workItem.getParameters().keySet()) {

            if (!(parameter.equals("TaskName") || parameter.equals("NodeName"))) {

                // Picking up the Object passed from workflow
                Object dataModelObject = workItem.getParameters().get(parameter);

                // Fetching the Data Operation to be performed
                if (Objects.nonNull(dataModelObject) && dataModelObject.getClass().getName().equals("java.lang.String")
                        && Set.of("FETCH", "FETCHALL", "INSERT", "UPDATE", "DELETE")
                                .contains(dataModelObject.toString().toUpperCase())) {
                    dataOperation = dataModelObject.toString().toUpperCase();
                }

                /*
                 * Finding the data model to operate on
                 * Need two functions as we can avoid issue of order of
                 * input in the BPMN process data
                 */
                if (Objects.nonNull(dataModelObject)
                        && dataModelObject.getClass().getName().indexOf(workspace) >= 0) {
                    dataOperationObject = dataModelObject;
                }

                // Only work if the operation and data model to be operated on is available
                if (dataOperation != null && dataOperationObject != null) {
                    String outputVariable = OlingoSupport.getOutputVariable(workItem,
                            dataOperationObject.getClass().getName());
                    switch (dataOperation) {
                        case "FETCH":
                            results.put(outputVariable, fetchData(dataOperationObject, top, skip, order, fetchParam));
                            break;
                        case "FETCHALL":
                            results.put(outputVariable, fetchAllData(dataOperationObject));
                            break;
                        case "INSERT":
                            results.put(outputVariable, insertData(dataOperationObject));
                            break;
                        case "UPDATE":
                            results.put(outputVariable, updateData(dataOperationObject));
                            break;
                        case "DELETE":
                            results.put(outputVariable, deleteData(dataOperationObject));
                            break;
                    }
                }

            }
        }

        // Don’t forget to finish the work item otherwise the process
        // will be active infinitely and never will pass the flow
        // to the next node.
        Log.info("Data Operations Ends!!");
        manager.completeWorkItem(workItem.getStringId(), results);

    }

    @Override
    public void abortWorkItem(KogitoWorkItem kogitoWorkItem, KogitoWorkItemManager kogitoWorkItemManager) {
        System.err.println("Error happened in the Fetch Details definition.");
    }

    /*
     * Function to fetch all the objects for a specific
     * DataModel type available in the Odata
     */
    private List<Object> fetchAllData(Object dataModelObject) {
        Log.info("Fetch All Data Operation Begins!!");
        List<Object> myList = new ArrayList<>();
        URI fetchURI = client.newURIBuilder(odataUrl + "/" + workspace + "/" + miniAppName)
                .appendEntitySetSegment(dataModelObject.getClass().getSimpleName()).build();
        ODataEntitySetRequest<ClientEntitySet> request = client.getRetrieveRequestFactory()
                .getEntitySetRequest(fetchURI);
        final ODataRetrieveResponse<ClientEntitySet> response = request.execute();
        final ClientEntitySet entitySet = response.getBody();
        List<ClientEntity> list = entitySet.getEntities();
        for (ClientEntity enty : list) {
            JSONObject obj = new JSONObject();
            List<ClientProperty> props = enty.getProperties();
            for (ClientProperty prop : props) {
                obj.put(prop.getName(), prop.getValue().toString());
                System.out.println("Property:::: " + prop.getValue());
            }
            myList.add(new Gson().fromJson(obj.toString(), dataModelObject.getClass()));
            System.out.println("All Data Fetched");
        }
        return myList;
    }

    /*
     * Function to Fetch the data for an Data Model Object
     * based on either the id or a query generated from
     * the properties of the specific model
     */
    private Object fetchData(Object dataModelObject, int top, int skip, String order, String fetchParam) {
        Log.info("FetchData Operation Begins!!");
        String dataModelName = dataModelObject.getClass().getSimpleName();
        int queryFlag = 0;
        String filterQuery = "";
        JSONObject resultObj = new JSONObject();
        URI fetchURI = null;
        URIBuilder initialURI = client.newURIBuilder(odataUrl + "/" + workspace + "/" + miniAppName)
                .appendEntitySetSegment(dataModelName);

        if (top != 0 && skip != 0) {
            fetchURI = initialURI.top(top).skip(skip).addQueryOption(QueryOption.ORDERBY, "_id " + order).build();
        } else if (top != 0) {
            fetchURI = initialURI.top(top).addQueryOption(QueryOption.ORDERBY, "_id " + order).build();
        } else if (skip != 0) {
            fetchURI = initialURI.skip(skip).addQueryOption(QueryOption.ORDERBY, "_id " + order).build();
        } else {

            // If Fetch Param is provided, search based on that
            if (Objects.nonNull(fetchParam)) {
                List<String> fetchParams = stringToList(fetchParam);
                for (String param : fetchParams) {
                    try {
                        Field paramField = dataModelObject.getClass().getDeclaredField(param);
                        paramField.setAccessible(true);
                        if (paramField.get(dataModelObject) != null) {
                            if (queryFlag == 0) {
                                // Adding the first field
                                filterQuery += paramField.getName() + " eq ";
                                queryFlag++;
                            } else {
                                // Adding the 2..n fields
                                filterQuery += " and " + paramField.getName() + " eq ";
                            }
                            // Adding the variable to the Query
                            if (paramField.getType().equals(String.class)) {
                                filterQuery += "'" + paramField.get(dataModelObject) + "'";
                            } else {
                                filterQuery += paramField.get(dataModelObject);
                            }
                        }

                    } catch (Exception e) {
                        Log.error("Error Occurred in Fetch Param Query");
                        e.printStackTrace();
                        return null;
                    }
                }
            } else {
                for (Field dataField : dataModelObject.getClass().getDeclaredFields()) {
                    dataField.setAccessible(true);
                    String dataType = dataField.getType().getName();
                    try {
                        // If id is available, then that will be used to fetch the details
                        if (dataField.get(dataModelObject) != null && dataField.getName().equals("_id")) {
                            filterQuery = "_id eq '" + dataField.get(dataModelObject) + "'";
                            break;
                        }

                        if (dataField.get(dataModelObject) != null
                                && dataField.getName() != "serialVersionUID"
                                && !dataType.equals("java.util.List") && !dataType.contains("io."+workspace+".generated.models")) {
                            // Constructing the Query
                            if (queryFlag == 0) {
                                // Adding the first field
                                filterQuery += dataField.getName() + " eq ";
                                queryFlag++;
                            } else {
                                // Adding the 2..n fields
                                filterQuery += " and " + dataField.getName() + " eq ";
                            }
                            // Adding the variable to the Query
                            if (dataField.getType().equals(String.class) || dataField.getType().equals(LocalDate.class)) {
                                filterQuery += "'" + dataField.get(dataModelObject) + "'";
                            } else {
                                filterQuery += dataField.get(dataModelObject);
                            }

                        }
                    } catch (IllegalAccessException e) {
                        Log.error("Object Access in query creation failed");
                        throw new RuntimeException(e);
                    }
                }
            }
            fetchURI = initialURI.filter(filterQuery).build();
        }
        ODataEntitySetIteratorRequest<ClientEntitySet, ClientEntity> request = client.getRetrieveRequestFactory()
                .getEntitySetIteratorRequest(fetchURI);
        request.setAccept("application/json");

        try {
            ODataRetrieveResponse<ClientEntitySetIterator<ClientEntitySet, ClientEntity>> response = request.execute();
            ClientEntitySetIterator<ClientEntitySet, ClientEntity> iterator = response.getBody();
            while (iterator.hasNext()) {
                ClientEntity ce = iterator.next();
                for (ClientProperty property : ce.getProperties()) {
                    if (property.hasCollectionValue()) {
                        resultObj.put(property.getName(), getComplexCollectionValue(property));
                    } else {
                        resultObj.put(property.getName(), property.getValue().toString());
                    }
                }
                break;
            }
        } catch (ODataClientErrorException e) {
            // Failure if the DataModel does not exist in the server
            Log.error("DataModel not Found in OData Server!!");
            e.printStackTrace();
        }

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new JsonDeserializer() {
                    @Override
                    public LocalDate deserialize(JsonElement json, Type type,
                            JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
                        return LocalDate.parse(json.getAsJsonPrimitive().getAsString());
                    }

                }).registerTypeAdapter(Date.class, new JsonDeserializer() {
                    @Override
                    public Date deserialize(JsonElement jsonElement, Type type,
                                              JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
                        for (String format : DATE_FORMATS) {
                            try {
                                return new SimpleDateFormat(format, Locale.US).parse(jsonElement.getAsString());
                            } catch (ParseException e) {
                            }
                        }
                        return null;
                    }
                }).create();

        return gson.fromJson(resultObj.toString(), dataModelObject.getClass());
    }

    /*
     * Function to update a model object based on the properties
     * passed
     */
    public Object updateData(Object dataModelObject) {
        Log.info("Update Data Operation Begins!!");
        String dataModelId = null;
        ClientEntity newEntity = client.getObjectFactory()
                .newEntity(new FullQualifiedName(dataModelObject.getClass().getName()));

        // Looping to find the values passed to be updated
        for (Field dataField : dataModelObject.getClass().getDeclaredFields()) {
            dataField.setAccessible(true);
            try {
                Object dataObject = dataField.get(dataModelObject);
                String dataType = dataField.getType().getName();
                ClientPrimitiveValue value = null;
                if (dataObject != null && dataField.getName() == "_id") {
                    dataModelId = dataObject.toString();
                } else if (dataType.equals("java.util.List")) {
                    // As a collection
                    if (!((ArrayList) dataObject).isEmpty()) {
                        ClientCollectionValue addressCollectionValue = constructComplexObjects(dataObject);
                        newEntity.getProperties()
                                .add(new ClientPropertyImpl(dataField.getName(), addressCollectionValue));
                        System.out.println("Complex Types Added");
                    }
                } else if (dataObject != null && dataField.getName() != "serialVersionUID") {
                    // TODO: Need to consider more data type or a shorthand
                    // Adding values to be updated to entity
                    if (dataType.equals("java.lang.Boolean")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().buildBoolean((Boolean) dataObject);
                    } else if (dataType.equals("java.lang.Integer")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt32(((Integer) dataObject));
                    } else if (dataType.equals("java.lang.Float")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().setType(EdmPrimitiveTypeKind.Single).setValue(dataObject).build();
                        //value = client.getObjectFactory().newPrimitiveValueBuilder().buildDouble((Double) dataObject);
                    } else if (dataType.equals("java.lang.Long")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt64((Long) dataObject);
                    } else if (dataType.equals("java.time.LocalDate")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().setType(EdmPrimitiveTypeKind.Date)
                                .setValue(dataObject).build();
                    } else if (dataType.equals("java.util.Date")) {
                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                .setType(EdmPrimitiveTypeKind.DateTimeOffset).setValue(dataObject).build();
                    } else {
                        value = client.getObjectFactory().newPrimitiveValueBuilder().buildString(dataObject.toString());
                    }

                    // Adding the value to be updated to the Entity
//                    newEntity.getProperties().add(
//                            client.getObjectFactory().newPrimitiveProperty(
//                                    dataField.getName(), value));

                    if(dataType.contains("io."+workspace+".generated.models")){
                        ClientComplexValue innerObj = buildComplexEntity(dataObject);
                        newEntity.getProperties()
                                .add(new ClientPropertyImpl(dataField.getName(), innerObj));
                    }
                    else {
                        newEntity.getProperties().add(
                                client.getObjectFactory().newPrimitiveProperty(
                                        dataField.getName(), value));
                    }
                }
            } catch (IllegalAccessException e) {
                Log.error("Object Access in query creation failed");
                throw new RuntimeException(e);
            }
        } // DataField loop ends here

        URI absoluteUri = client.newURIBuilder(odataUrl + "/" + workspace + "/" + miniAppName)
                .appendEntitySetSegment(dataModelObject.getClass().getSimpleName()).appendKeySegment(dataModelId)
                .build();

        try {
            ODataEntityUpdateRequest<ClientEntity> request = client.getCUDRequestFactory()
                    .getEntityUpdateRequest(absoluteUri, UpdateType.PATCH, newEntity);
            ODataEntityUpdateResponse<ClientEntity> response = request.execute();
            System.out.println("Update completed");
        } catch (ODataClientErrorException e) {
            // Failure if the DataModel does not exist in the server
            Log.error("DataModel not Found in OData Server!!");
            e.printStackTrace();
        }

        // TODO: Should we return the updated object calling it again
        return dataModelObject;
    }

    /*
     * Function to insert a new entity into the Odata
     * server with the properties passed as parameter
     */
    public Object insertData(Object dataModelObject) {
        Log.info("Insert Data Operation Begins!!");
        ClientEntity newEntity = client.getObjectFactory()
                .newEntity(new FullQualifiedName(dataModelObject.getClass().getName()));

        // Looping to find the values passed to be updated
        for (Field dataField : dataModelObject.getClass().getDeclaredFields()) {
            dataField.setAccessible(true);

            try {
                Object dataObject = dataField.get(dataModelObject);
                String dataType = dataField.getType().getName();
                ClientPrimitiveValue value = null;

                if (dataObject != null &&
                        dataField.getName() != "serialVersionUID" &&
                        dataField.getName() != "_id") {
                    // TODO: Need to consider more data type or a shorthand

                    if (dataType.equals("java.util.List")) {
                        // As a collection
                        if (!((ArrayList) dataObject).isEmpty()) {
                            ClientCollectionValue addressCollectionValue = constructComplexObjects(dataObject);
                            newEntity.getProperties()
                                    .add(new ClientPropertyImpl(dataField.getName(), addressCollectionValue));
                            System.out.println("Complex Types Added");
                        }
                    } else {
                        // Adding values to be updated to entity
                        if (dataType.equals("java.lang.Boolean")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildBoolean((Boolean) dataObject);
                        } else if (dataType.equals("java.lang.Integer")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildInt32((Integer) dataObject);
                        } else if (dataType.equals("java.lang.Float")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .setType(EdmPrimitiveTypeKind.Single).setValue(dataObject).build();
                            //value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    //.buildDouble((Double) dataObject);
                        } else if (dataType.equals("java.lang.Long")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt64((Long) dataObject);
                        } else if (dataType.equals("java.time.LocalDate")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .setType(EdmPrimitiveTypeKind.Date).setValue(dataObject).build();
                        } else if (dataType.equals("java.util.Date")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .setType(EdmPrimitiveTypeKind.DateTimeOffset).setValue(dataObject).build();
                        } else {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildString(dataObject.toString());
                        }
                        // Adding the value to be updated to the Entity
                        if(dataType.contains("io."+workspace+".generated.models")){
                            ClientComplexValue innerObj = buildComplexEntity(dataObject);
                            newEntity.getProperties()
                                    .add(new ClientPropertyImpl(dataField.getName(), innerObj));
                        }
                        else {
                            newEntity.getProperties().add(
                                    client.getObjectFactory().newPrimitiveProperty(
                                            dataField.getName(), value));
                        }
                    }
                }
            } catch (IllegalAccessException e) {
                Log.error("Object Access in query creation failed");
                throw new RuntimeException(e);
            }
        } // DataField Loop ends

        URI absoluteUri = client.newURIBuilder(odataUrl + "/" + workspace + "/" + miniAppName)
                .appendEntitySetSegment(dataModelObject.getClass().getSimpleName()).build();
        ODataEntityCreateRequest<ClientEntity> request = client.getCUDRequestFactory()
                .getEntityCreateRequest(absoluteUri, newEntity);
        request.setAccept("application/json");
        ODataEntityCreateResponse<ClientEntity> response = request.execute();
        ClientEntity respo = response.getBody();

        // TODO:Return the newly created entity with id
        return dataModelObject;
    }

    private ClientComplexValue buildComplexEntity(Object dataModelObject) {
        ClientComplexValue complexValue = client.getObjectFactory().newComplexValue(dataModelObject.getClass().getName());
        for (Field dataField : dataModelObject.getClass().getDeclaredFields()) {
            dataField.setAccessible(true);
            try {
                Object dataObject = dataField.get(dataModelObject);
                String dataType = dataField.getType().getName();
                ClientPrimitiveValue value;

                if (dataObject != null &&
                        !"serialVersionUID".equals(dataField.getName()) &&
                        !"_id".equals(dataField.getName())) {

                    if (dataType.equals("java.util.List")) {
                        // Handle collection (list) types
                        List<?> dataList = (List<?>) dataObject;
                        if (!dataList.isEmpty()) {
                            ClientCollectionValue collectionValue = client.getObjectFactory().newCollectionValue(null);
                            for (Object item : dataList) {
                                if (item instanceof String || item instanceof Boolean || item instanceof Integer ||
                                        item instanceof Float || item instanceof Long || item instanceof LocalDate ||
                                        item instanceof Date) {
                                    // Handle list of primitive values
                                    value = createPrimitiveValue(item);
                                    collectionValue.add(value);
                                } else {
                                    // Handle list of complex values
                                    ClientComplexValue itemComplexValue = buildComplexEntity(item);
                                    collectionValue.add(itemComplexValue);
                                }
                            }
                            complexValue.add(client.getObjectFactory().newCollectionProperty(dataField.getName(), collectionValue));
                        }
                    } else {
                        // Handle non-collection (single) values
                        value = createPrimitiveValue(dataObject);
                        complexValue.add(client.getObjectFactory().newPrimitiveProperty(dataField.getName(), value));
                    }
                }
            } catch (IllegalAccessException e) {
                Log.error("Object Access in query creation failed");
                e.printStackTrace();
            }
        }
        return complexValue;
    }

    private ClientPrimitiveValue createPrimitiveValue(Object dataObject) {
        String dataType = dataObject.getClass().getName();
        ClientPrimitiveValue value;

        switch (dataType) {
            case "java.lang.Boolean":
                value = client.getObjectFactory().newPrimitiveValueBuilder().buildBoolean((Boolean) dataObject);
                break;
            case "java.lang.Integer":
                value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt32((Integer) dataObject);
                break;
            case "java.lang.Float":
                value = client.getObjectFactory().newPrimitiveValueBuilder().setType(EdmPrimitiveTypeKind.Single).setValue(dataObject).build();
                //value = client.getObjectFactory().newPrimitiveValueBuilder().buildDouble((Double) dataObject);
                break;
            case "java.lang.Long":
                value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt64((Long) dataObject);
                break;
            case "java.time.LocalDate":
                value = client.getObjectFactory().newPrimitiveValueBuilder()
                        .setType(EdmPrimitiveTypeKind.Date).setValue(dataObject).build();
                break;
            case "java.util.Date":
                value = client.getObjectFactory().newPrimitiveValueBuilder()
                        .setType(EdmPrimitiveTypeKind.DateTimeOffset).setValue(dataObject).build();
                break;
            default:
                value = client.getObjectFactory().newPrimitiveValueBuilder().buildString(dataObject.toString());
                break;
        }

        return value;
    }


    /*
     * Function to delete an object according to the
     * id of the object in the OData server
     */
    private Object deleteData(Object dataModelObject) {
        Log.info("Delete Data Operation Begins!!");
        for (Field dataField : dataModelObject.getClass().getDeclaredFields()) {
            dataField.setAccessible(true);

            try {
                Object dataObject = dataField.get(dataModelObject);

                if (dataObject != null && dataField.getName().equals("_id")) {

                    URI absoluteUri = client.newURIBuilder(odataUrl + "/" + workspace + "/" + miniAppName)
                            .appendEntitySetSegment(dataModelObject.getClass().getSimpleName())
                            .appendKeySegment(dataField.get(dataModelObject)).build();
                    ODataDeleteRequest request = client.getCUDRequestFactory().getDeleteRequest(absoluteUri);
                    request.setAccept("application/json;odata.metadata=minimal");
                    ODataDeleteResponse response = request.execute();
                }
            } catch (IllegalAccessException e) {
                Log.error("Object Access in query creation failed");
                throw new RuntimeException(e);
            }

        }

        // TODO:What to return on successful delete
        return null;
    }

    private ClientCollectionValue constructComplexObjects(Object dataObject) {
        // TODO:Optimize and avoid code repetition
        try {
            if (!((ArrayList) dataObject).isEmpty()) {
                ClientCollectionValue addressCollectionValue = new ClientCollectionValueImpl(
                        ((ArrayList) dataObject).get(0).getClass().getName());
                for (int i = 0; i < ((ArrayList) dataObject).size(); i++) {
                    ClientComplexValue complexValue = client.getObjectFactory().newComplexValue(
                            ((ArrayList) dataObject).get(0).getClass().getName());
                    ClientPrimitiveValue value = null;

                    // For List of Primitive or String values
                    if (((ArrayList) dataObject).get(i).getClass().getName().equals("java.lang.String") ||
                            ((ArrayList) dataObject).get(i).getClass().isPrimitive()) {

                        Log.info("Inner List of Primitive Types and Strings");
                        String dataType = ((ArrayList) dataObject).get(i).getClass().getName();
                        Object valueObject = ((ArrayList) dataObject).get(i);
                        if (dataType.equals("java.lang.Boolean")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildBoolean((Boolean) valueObject);
                        } else if (dataType.equals("java.lang.Integer")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildInt32((Integer) valueObject);
                        } else if (dataType.equals("java.lang.Float")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder().setType(EdmPrimitiveTypeKind.Single).setValue(valueObject).build();
                            //value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    //.buildDouble((Double) valueObject);
                        } else if (dataType.equals("java.lang.Long")) {
                            value = client.getObjectFactory().newPrimitiveValueBuilder().buildInt64((Long) valueObject);
                        } else {
                            value = client.getObjectFactory().newPrimitiveValueBuilder()
                                    .buildString(valueObject.toString());
                        }
                        addressCollectionValue.add(value);
                    } else {
                        // For List of non-Primitive Objects
                        for (Field dataField : ((ArrayList) dataObject).get(i).getClass().getDeclaredFields()) {
                            dataField.setAccessible(true);
                            if (dataField.getName() != "serialVersionUID" &&
                                    dataField.getName() != "_id") {
                                String dataType = dataField.getType().getName();
                                Object valueObject = dataField.get(((ArrayList) dataObject).get(i));

                                if (dataType.equals("java.util.List")) {
                                    if (Objects.nonNull(valueObject) && !((ArrayList) valueObject).isEmpty()) {
                                        complexValue.add(new ClientPropertyImpl(dataField.getName(),
                                                constructComplexObjects(valueObject)));
                                    }
                                } else if (dataType.equals("java.lang.String") ||
                                        dataType.getClass().isPrimitive()) {
                                    // Adding values to be updated to entity
                                    if (dataType.equals("java.lang.Boolean")) {
                                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                .buildBoolean((Boolean) valueObject);
                                    } else if (dataType.equals("java.lang.Integer")) {
                                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                .buildInt32((Integer) valueObject);
                                    } else if (dataType.equals("java.lang.Float")) {
                                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                .setType(EdmPrimitiveTypeKind.Single).setValue(valueObject).build();
                                        //value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                //.buildDouble((Double) valueObject);
                                    } else if (dataType.equals("java.lang.Long")) {
                                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                .buildInt64((Long) valueObject);
                                    } else {
                                        value = client.getObjectFactory().newPrimitiveValueBuilder()
                                                .buildString(valueObject.toString());
                                    }
                                    complexValue.add(new ClientPropertyImpl(dataField.getName(), value));
                                } else if (Objects.nonNull(valueObject)) {
                                    ArrayList<Object> temPList = new ArrayList<>();
                                    temPList.add(valueObject);
                                    complexValue.add(new ClientPropertyImpl(dataField.getName(),
                                            constructComplexObjects(temPList)));
                                }
                            }
                        }
                        addressCollectionValue.add(complexValue);
                    }
                }
                return addressCollectionValue;
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private JSONArray getComplexCollectionValue(ClientProperty property) {
        ClientCollectionValue<ClientValue> resultCollection = property.getCollectionValue();
        JSONArray complexArray = new JSONArray();
        for (ClientValue complexData : resultCollection) {
            if (complexData.isComplex()) {
                ClientComplexValue complexValue = (ClientComplexValue) complexData;
                Iterator<ClientProperty> complexProps = complexValue.iterator();
                JSONObject complexObj = new JSONObject();
                while (complexProps.hasNext()) {
                    ClientProperty complex = complexProps.next();
                    if (complex.hasCollectionValue()) {
                        complexObj.put(complex.getName(), getComplexCollectionValue(complex));
                    } else {
                        complexObj.put(complex.getName(), complex.getValue().toString());
                    }
                }
                complexArray.add(complexObj);
            } else {
                complexArray.add(complexData);
            }

        }
        return complexArray;
    }

    private List<String> stringToList(String data) {
        List<String> dataList = new ArrayList<>();
        if (data.contains(";")) {
            // Creating a list of to address
            dataList = Arrays.asList(data.split(";"));
        } else {
            dataList.add(data);
        }
        return dataList;
    }

}
