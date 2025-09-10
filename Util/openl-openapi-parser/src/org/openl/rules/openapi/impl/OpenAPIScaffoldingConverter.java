package org.openl.rules.openapi.impl;

import static org.openl.rules.openapi.impl.OpenAPITypeUtils.LINK_TO_DEFAULT_RUNTIME_CONTEXT;
import static org.openl.rules.openapi.impl.OpenAPITypeUtils.SCHEMAS_LINK;
import static org.openl.rules.openapi.impl.OpenAPITypeUtils.getSimpleName;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.APPLICATION_JSON;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.TEXT_PLAIN;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.getSchemas;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.normalizeName;
import static org.openl.rules.openapi.impl.OpenLOpenAPIUtils.resolve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.apache.commons.lang3.tuple.Pair;

import org.openl.rules.calc.SpreadsheetResult;
import org.openl.rules.model.scaffolding.DatatypeModel;
import org.openl.rules.model.scaffolding.FieldModel;
import org.openl.rules.model.scaffolding.InputParameter;
import org.openl.rules.model.scaffolding.MethodModel;
import org.openl.rules.model.scaffolding.PathInfo;
import org.openl.rules.model.scaffolding.ProjectModel;
import org.openl.rules.model.scaffolding.SpreadsheetModel;
import org.openl.rules.model.scaffolding.StepModel;
import org.openl.rules.model.scaffolding.TypeInfo;
import org.openl.rules.model.scaffolding.data.DataModel;
import org.openl.rules.openapi.OpenAPIModelConverter;
import org.openl.rules.openapi.OpenAPIRefResolver;
import org.openl.util.CollectionUtils;
import org.openl.util.StringUtils;

public class OpenAPIScaffoldingConverter implements OpenAPIModelConverter {

    public static final String SPREADSHEET_RESULT = "SpreadsheetResult";
    public static final String SPR_RESULT_LINK = SCHEMAS_LINK + SPREADSHEET_RESULT;
    public static final String RESULT = "Result";
    public static final Pattern PARAMETERS_BRACKETS_MATCHER = Pattern.compile("\\{.*?}");
    private static final Set<String> IGNORED_FIELDS = Set.copyOf(Collections.singletonList("@class"));
    public static final String SPREADSHEET_RESULT_CLASS_NAME = SpreadsheetResult.class.getName();
    public static final String GET_PREFIX = "get";

    public OpenAPIScaffoldingConverter() {
        // default constructor
    }

    @Override
    public ProjectModel extractProjectModel(String pathTo) {
        ParseOptions options = OpenLOpenAPIUtils.getParseOptions();
        OpenAPI openAPI = new OpenAPIV3Parser().read(pathTo, null, options);
        if (openAPI == null) {
            throw new IllegalStateException("Error creating the project, uploaded file has invalid structure.");
        }
        OpenAPIRefResolver openAPIRefResolver = new OpenAPIRefResolver(openAPI);

        String projectName = openAPI.getInfo().getTitle();

        Paths paths = openAPI.getPaths();

        Map<String, Integer> allUsedSchemaRefs = OpenLOpenAPIUtils.getAllUsedSchemaRefs(paths, openAPIRefResolver);

        Map<String, Map<String, Integer>> pathsWithRequestsRefs = OpenLOpenAPIUtils.collectPathsWithParams(paths,
                openAPIRefResolver);

        Map<String, Integer> allUsedSchemaRefsInRequests = pathsWithRequestsRefs.values()
                .stream()
                .flatMap(m -> m.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum));

        boolean isRuntimeContextProvided = allUsedSchemaRefsInRequests.keySet()
                .stream()
                .anyMatch(LINK_TO_DEFAULT_RUNTIME_CONTEXT::equals);

        Set<String> allUnusedRefs = OpenLOpenAPIUtils.getUnusedSchemaRefs(openAPI, allUsedSchemaRefs.keySet());

        Map<String, List<String>> childrenSchemas = OpenAPITypeUtils.getChildrenMap(openAPI);
        Set<String> parents = childrenSchemas.keySet();
        Set<String> childSet = childrenSchemas.values()
                .stream()
                .flatMap(Collection::stream)
                .map(OpenAPITypeUtils::getSimpleName)
                .collect(Collectors.toSet());

        Map<String, Set<String>> refsWithFields = OpenLOpenAPIUtils.getRefsInProperties(openAPI, openAPIRefResolver);
        Set<String> fieldsRefs = refsWithFields.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        // all the requests which were used only once per project needed to be extracted
        // if it's extends from other model it will be an inline type
        Set<String> refsToExpand = allUsedSchemaRefsInRequests.entrySet().stream().filter(refWithCount -> {
            String ref = refWithCount.getKey();
            Integer numberOfRefUsage = refWithCount.getValue();
            boolean refIsParentOrField = !parents.contains(ref) && !fieldsRefs.contains(ref);
            boolean refIsNotChild = !childSet.contains(getSimpleName(ref));
            boolean refIsNotRuntimeContext = !ref.equals(LINK_TO_DEFAULT_RUNTIME_CONTEXT);
            return refIsNotRuntimeContext && numberOfRefUsage
                    .equals(1) && (!allUsedSchemaRefs.containsKey(ref) || allUsedSchemaRefs.get(ref)
                    .equals(1)) && refIsParentOrField && refIsNotChild;
        }).map(Map.Entry::getKey).collect(Collectors.toSet());

        Map<Pair<String, PathItem.HttpMethod>, Set<String>> refsByPathAndOperation = OpenLOpenAPIUtils
                .getAllUsedRefResponses(paths, openAPIRefResolver);

        // all the path methods which have primitive responses are possible spreadsheets too
        Set<Pair<String, PathItem.HttpMethod>> primitiveReturnPathOperations = refsByPathAndOperation.entrySet()
                .stream()
                .filter(pathWithOperation -> pathWithOperation.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // searching for path methods which response models are not included in ANY requestBody
        Map<Pair<String, PathItem.HttpMethod>, Set<String>> operationsPathWithPotentialSpr = refsByPathAndOperation
                .entrySet()
                .stream()
                .filter(pathOperation -> !pathOperation.getValue().isEmpty() && pathOperation.getValue()
                        .stream()
                        .noneMatch(allUsedSchemaRefsInRequests::containsKey))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Set<Pair<String, PathItem.HttpMethod>> spreadsheetOperations = refsByPathAndOperation.keySet()
                .stream()
                .filter(pathWithOperation -> !operationsPathWithPotentialSpr
                        .containsKey(pathWithOperation) && !primitiveReturnPathOperations.contains(pathWithOperation))
                .collect(Collectors.toSet());

        Set<String> spreadsheetResultRefs = operationsPathWithPotentialSpr.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        List<SpreadsheetParserModel> spreadsheetParserModels = extractSprModels(paths,
                openAPIRefResolver,
                operationsPathWithPotentialSpr.keySet(),
                primitiveReturnPathOperations,
                spreadsheetOperations,
                refsToExpand,
                childSet);
        Set<String> dataModelRefs = new HashSet<>();
        List<DataModel> dataModels = extractDataModels(spreadsheetParserModels,
                openAPIRefResolver,
                openAPI,
                spreadsheetResultRefs,
                dataModelRefs);
        // self-linked, array/child returned
        List<String> linkedRefs = spreadsheetParserModels.stream()
                .filter(SpreadsheetParserModel::isRefIsDataType)
                .map(SpreadsheetParserModel::getReturnRef)
                .collect(Collectors.toList());
        Set<String> datatypeRefs = allUsedSchemaRefs.keySet().stream().filter(x -> {
            boolean notSpreadsheetAndExpanded = !(spreadsheetResultRefs.contains(x) || refsToExpand.contains(x));
            boolean isNotReserved = !x.equals(SPR_RESULT_LINK);
            boolean notDatatype = linkedRefs.contains(x);
            return isNotReserved && (notSpreadsheetAndExpanded || notDatatype);
        }).collect(Collectors.toSet());

        Set<String> refSpreadsheets = spreadsheetParserModels.stream()
                .filter(x -> !x.isRefIsDataType() && x.getReturnRef() != null)
                .map(SpreadsheetParserModel::getReturnRef)
                .collect(Collectors.toSet());

        Set<String> allFieldsRefs = retrieveAllFieldsRefs(datatypeRefs, refsWithFields);
        // case when any datatype has a link in a field to the spreadsheet
        Set<String> dtToAdd = allFieldsRefs.stream().filter(x -> {
            boolean isNotSpreadsheetResult = !SPR_RESULT_LINK.equals(x);
            boolean isNotPresentedInDataTypes = !datatypeRefs.contains(x);
            return isNotSpreadsheetResult && isNotPresentedInDataTypes;
        }).collect(Collectors.toSet());

        // If there is a datatype to add which was returned by any spreadsheet model, it will be transformed
        spreadsheetParserModels.stream().filter(x -> dtToAdd.contains(x.getReturnRef())).forEach(x -> {
            SpreadsheetModel model = x.getModel();
            String type = OpenAPITypeUtils.getSimpleName(x.getReturnRef());
            model.setType(type);
            model.getPathInfo().setReturnType(new TypeInfo(type, type, TypeInfo.Type.DATATYPE));
            model.setSteps(makeSingleStep(type));
        });

        fillCallsInSteps(spreadsheetParserModels, datatypeRefs, dataModelRefs, dtToAdd);

        datatypeRefs.addAll(dtToAdd);
        refSpreadsheets.removeAll(dtToAdd);

        Set<DatatypeModel> dts = new LinkedHashSet<>(extractDataTypeModels(openAPIRefResolver, openAPI, datatypeRefs));
        dts.addAll(extractDataTypeModels(openAPIRefResolver, openAPI, allUnusedRefs));

        Set<String> usedInDataTypes = new HashSet<>();
        // searching for links in data types
        dts.forEach(dt -> {
            Set<String> set = dt.getFields().stream().map(FieldModel::getType).collect(Collectors.toSet());
            if (!set.contains(dt.getName())) {
                dt.getFields()
                        .stream()
                        .filter(fieldModel -> !OpenAPITypeUtils.isSimpleType(fieldModel.getType()))
                        .map(fieldModel -> OpenAPITypeUtils.removeArrayBrackets(fieldModel.getType()))
                        .forEach(usedInDataTypes::add);
            }
        });
        // if no links from data types, but model has links to the spreadsheets -> it will be a spreadsheet
        // any spreadsheet result filtering there to avoid the broken project
        List<String> notUsedDataTypeWithRefToSpreadsheet = dts.stream()
                .filter(x -> !usedInDataTypes.contains(x.getName()))
                .map(x -> Pair.of(x.getName(), x.getFields()))
                .filter(y -> y.getRight()
                        .stream()
                        .anyMatch(field -> refSpreadsheets
                                .contains(SCHEMAS_LINK + OpenAPITypeUtils.removeArrayBrackets(field.getType()))))
                .map(Pair::getLeft)
                .collect(Collectors.toList());

        dts.removeIf(
                x -> notUsedDataTypeWithRefToSpreadsheet.contains(x.getName()) || SPREADSHEET_RESULT.equals(x.getName()));
        // create spreadsheet from potential models
        createLostSpreadsheets(openAPIRefResolver,
                openAPI,
                spreadsheetParserModels,
                refSpreadsheets,
                notUsedDataTypeWithRefToSpreadsheet,
                pathsWithRequestsRefs,
                isRuntimeContextProvided);
        // change steps with in the spreadsheets to these potential models
        setCallsAndReturnTypeToLostSpreadsheet(spreadsheetParserModels, notUsedDataTypeWithRefToSpreadsheet);

        Set<String> dtNames = dts.stream().map(DatatypeModel::getName).collect(Collectors.toSet());
        checkTypes(spreadsheetParserModels, dtNames);

        final Consumer<MethodModel> applyInclude = method -> method
                .setInclude(paths.get(method.getPathInfo().getOriginalPath()) != null);
        List<SpreadsheetModel> spreadsheetModels = spreadsheetParserModels.stream()
                .map(SpreadsheetParserModel::getModel)
                .collect(Collectors.toList());
        spreadsheetModels.forEach(applyInclude);
        dataModels.forEach(applyInclude);

        Map<Boolean, List<SpreadsheetModel>> sprModelsDivided = spreadsheetModels.stream()
                .collect(Collectors.partitioningBy(spreadsheetModel -> containsRuntimeContext(
                        pathsWithRequestsRefs.get(spreadsheetModel.getPathInfo().getOriginalPath()))));
        List<SpreadsheetModel> sprModelsWithRC = sprModelsDivided.get(Boolean.TRUE);

        // remove defaultRuntimeContext from dts - it will be generated automatically in the interface
        dts.removeIf(dt -> dt.getName().equals(OpenAPITypeUtils.DEFAULT_RUNTIME_CONTEXT));

        removeContextFromParams(sprModelsWithRC);
        return new ProjectModel(projectName,
                isRuntimeContextProvided,
                dts,
                dataModels,
                isRuntimeContextProvided ? sprModelsWithRC : spreadsheetModels,
                isRuntimeContextProvided ? sprModelsDivided.get(Boolean.FALSE) : Collections.emptyList());
    }

    private Set<String> retrieveAllFieldsRefs(Set<String> datatypeRefs, Map<String, Set<String>> refsWithFields) {
        Set<String> allFieldsRefs = new HashSet<>();
        Queue<String> queue = new LinkedList<>(datatypeRefs);
        while (!queue.isEmpty()) {
            final String dtRef = queue.poll();
            refsWithFields.getOrDefault(dtRef, Collections.emptySet())
                    .stream()
                    .filter(x -> !datatypeRefs.contains(x) && !allFieldsRefs.contains(x))
                    .filter(allFieldsRefs::add)
                    .forEach(queue::add);
        }
        return allFieldsRefs;
    }

    private void checkTypes(List<SpreadsheetParserModel> parserModels, Set<String> dataTypeNames) {
        for (SpreadsheetParserModel parserModel : parserModels) {
            SpreadsheetModel model = parserModel.getModel();
            PathInfo pathInfo = model.getPathInfo();
            if (pathInfo != null) {
                TypeInfo returnType = pathInfo.getReturnType();
                if (dataTypeNames.contains(OpenAPITypeUtils.removeArrayBrackets(returnType.getSimpleName()))) {
                    returnType.setType(TypeInfo.Type.DATATYPE);
                }
            }

            List<InputParameter> parameters = model.getParameters();
            for (InputParameter parameter : parameters) {
                TypeInfo type = parameter.getType();
                if (dataTypeNames.contains(OpenAPITypeUtils.removeArrayBrackets(type.getSimpleName()))) {
                    type.setType(TypeInfo.Type.DATATYPE);
                }
            }
        }
    }

    private void setCallsAndReturnTypeToLostSpreadsheet(List<SpreadsheetParserModel> spreadsheetParserModels,
                                                        List<String> notUsedDataTypeWithRefToSpreadsheet) {
        if (!notUsedDataTypeWithRefToSpreadsheet.isEmpty()) {
            for (SpreadsheetParserModel spreadsheetParserModel : spreadsheetParserModels) {
                SpreadsheetModel sprModel = spreadsheetParserModel.getModel();
                String returnType = OpenAPITypeUtils.removeArrayBrackets(sprModel.getType());
                if (notUsedDataTypeWithRefToSpreadsheet.contains(returnType)) {
                    PathInfo pathInfo = sprModel.getPathInfo();
                    TypeInfo pathReturnType = pathInfo.getReturnType();
                    int dimension = pathReturnType.getDimension();
                    if (dimension == 0) {
                        sprModel.setType(SPREADSHEET_RESULT);
                        pathInfo.setReturnType(
                                new TypeInfo(SPREADSHEET_RESULT_CLASS_NAME, SPREADSHEET_RESULT, TypeInfo.Type.SPREADSHEET));
                    } else {
                        sprModel.setType(
                                SPREADSHEET_RESULT + returnType + String.join("", Collections.nCopies(dimension, "[]")));
                        pathReturnType.setJavaName(OpenAPITypeUtils.getSpreadsheetArrayClassName(dimension));
                        pathReturnType.setType(TypeInfo.Type.SPREADSHEET);
                    }
                }
                for (StepModel model : sprModel.getSteps()) {
                    String type = model.getType();
                    String simpleType = OpenAPITypeUtils.removeArrayBrackets(type);
                    if (notUsedDataTypeWithRefToSpreadsheet.contains(simpleType)) {
                        String call = makeCall(type, "");
                        model.setValue(type.endsWith("[]") ? makeArrayCall(type, simpleType, "") : "= " + call);
                    }
                }
            }
        }
    }

    private void createLostSpreadsheets(OpenAPIRefResolver openAPIRefResolver,
                                        OpenAPI openAPI,
                                        List<SpreadsheetParserModel> spreadsheetParserModels,
                                        Set<String> refSpreadsheets,
                                        List<String> notUsedDataTypeWithRefToSpreadsheet,
                                        Map<String, Map<String, Integer>> pathsWithRequestsRefs,
                                        boolean isRuntimeContextProvided) {
        for (String modelName : notUsedDataTypeWithRefToSpreadsheet) {
            SpreadsheetParserModel lostModel = new SpreadsheetParserModel();
            SpreadsheetModel model = new SpreadsheetModel();
            model.setName(modelName);
            model.setType(SPREADSHEET_RESULT);
            model.setParameters(Collections.emptyList());
            Schema<?> schema = getSchemas(openAPI).get(modelName);
            List<StepModel> steps = new ArrayList<>();
            if (schema != null) {
                Map<String, Schema> properties = schema.getProperties();
                if (CollectionUtils.isNotEmpty(properties)) {
                    steps = properties.entrySet()
                            .stream()
                            .filter(propertyEntry -> !IGNORED_FIELDS.contains(propertyEntry.getKey()))
                            .map(propertyEntry -> createStep(openAPIRefResolver,
                                    spreadsheetParserModels,
                                    refSpreadsheets,
                                    modelName,
                                    propertyEntry))
                            .collect(Collectors.toList());
                }
            }
            model.setSteps(steps);
            String originalPath = "/" + modelName;
            model.setPathInfo(new PathInfo(originalPath,
                    modelName,
                    PathInfo.Operation.POST,
                    new TypeInfo(SPREADSHEET_RESULT_CLASS_NAME, SPREADSHEET_RESULT, TypeInfo.Type.SPREADSHEET)));
            lostModel.setModel(model);
            spreadsheetParserModels.add(lostModel);
            if (isRuntimeContextProvided && !pathsWithRequestsRefs.containsKey(originalPath)) {
                Map<String, Integer> mapWithRC = new HashMap<>();
                mapWithRC.put(LINK_TO_DEFAULT_RUNTIME_CONTEXT, 1);
                pathsWithRequestsRefs.put(originalPath, mapWithRC);
            }
        }
    }

    private StepModel createStep(OpenAPIRefResolver openAPIRefResolver,
                                 List<SpreadsheetParserModel> spreadsheetParserModels,
                                 Set<String> refSpreadsheets,
                                 String modelName,
                                 Map.Entry<String, Schema> propertyEntry) {
        StepModel step = extractStep(openAPIRefResolver, propertyEntry);
        TypeInfo typeInfo = OpenAPITypeUtils.extractType(openAPIRefResolver, propertyEntry.getValue(), false);
        String stepType = typeInfo.getSimpleName();
        String type = OpenAPITypeUtils.removeArrayBrackets(stepType);
        String modelToCall = "";
        String value = "";
        if (!type.equals(modelName) && !refSpreadsheets.contains(SCHEMAS_LINK + type)) {
            return step;
        }
        if (type.equals(modelName)) {
            modelToCall = modelName;
        } else {
            Optional<SpreadsheetParserModel> optionalModel = Optional.empty();
            for (SpreadsheetParserModel parserModel : spreadsheetParserModels) {
                int dimension = parserModel.getModel().getPathInfo().getReturnType().getDimension();
                String returnRef = parserModel.getReturnRef();
                if (returnRef != null && returnRef.equals(SCHEMAS_LINK + type) && dimension == 0) {
                    optionalModel = Optional.of(parserModel);
                    break;
                }
            }
            if (optionalModel.isPresent()) {
                SpreadsheetParserModel spreadsheetParserModel = optionalModel.get();
                modelToCall = spreadsheetParserModel.getModel().getName();
                value = spreadsheetParserModel.getModel()
                        .getParameters()
                        .stream()
                        .map(InputParameter::getType)
                        .filter(t -> t.getType() != TypeInfo.Type.RUNTIMECONTEXT)
                        .map(OpenAPITypeUtils::getJavaDefaultValue)
                        .collect(Collectors.joining(", "));
            }
        }
        String call = makeCall(modelToCall, value);
        if (stepType.endsWith("[]")) {
            step.setValue(makeArrayCall(stepType, modelToCall, call));
        } else {
            step.setValue("= " + call);
        }
        return step;
    }

    private List<DataModel> extractDataModels(List<SpreadsheetParserModel> spreadsheetModels,
                                              OpenAPIRefResolver openAPIRefResolver,
                                              OpenAPI openAPI,
                                              Set<String> sprResultRefs,
                                              Set<String> dataModelsRefs) {
        List<SpreadsheetParserModel> potentialDataModels = spreadsheetModels.stream()
                .filter(x -> x.getModel()
                        .getPathInfo()
                        .getFormattedPath()
                        .startsWith(GET_PREFIX) && (CollectionUtils
                        .isEmpty(x.getModel().getParameters()) || containsOnlyRuntimeContext(x.getModel().getParameters())))
                .collect(Collectors.toList());
        List<DataModel> dataModels = new ArrayList<>();
        for (SpreadsheetParserModel potentialDataModel : potentialDataModels) {
            final TypeInfo returnType = potentialDataModel.getModel().getPathInfo().getReturnType();
            String type = OpenAPITypeUtils.removeArrayBrackets(returnType.getSimpleName());
            if (returnType.getDimension() == 0 || type.equals(SPREADSHEET_RESULT)) {
                continue;
            }
            PathInfo potentialDataTablePathInfo = potentialDataModel.getModel().getPathInfo();
            String operationMethod = potentialDataTablePathInfo.getOperation().name();
            // if get operation without parameters or post with only runtime context
            List<InputParameter> parameters = potentialDataModel.getModel().getParameters();
            boolean parametersNotEmpty = CollectionUtils.isNotEmpty(parameters);
            boolean getAndNoParams = parameters.isEmpty() && operationMethod.equals(PathItem.HttpMethod.GET.name());
            boolean postAndRuntimeContext = parametersNotEmpty && operationMethod
                    .equals(PathItem.HttpMethod.POST.name());
            if (getAndNoParams || postAndRuntimeContext) {
                String returnRef = potentialDataModel.getReturnRef();
                if (returnRef != null) {
                    sprResultRefs.remove(returnRef);
                    dataModelsRefs.add(returnRef);
                }
                spreadsheetModels.remove(potentialDataModel);
                String dataTableName = formatTableName(potentialDataModel.getModel().getName());
                potentialDataTablePathInfo.setFormattedPath(GET_PREFIX + dataTableName);

                boolean isSimpleType = OpenAPITypeUtils.isSimpleType(type);
                DataModel dataModel = new DataModel(dataTableName,
                        type,
                        potentialDataTablePathInfo,
                        isSimpleType ? createSimpleModel(type)
                                : createModelForDataTable(openAPIRefResolver,
                                openAPI,
                                type,
                                getSchemas(openAPI).get(type)));

                TypeInfo.Type resultType = isSimpleType ? TypeInfo.Type.OBJECT : TypeInfo.Type.DATATYPE;
                dataModel.getPathInfo().getReturnType().setType(resultType);
                if (parametersNotEmpty) {
                    dataModel.getPathInfo().setRuntimeContextParameter(parameters.iterator().next());
                }
                dataModels.add(dataModel);
            }
        }
        return dataModels;
    }

    private void removeContextFromParams(List<SpreadsheetModel> sprModelsWithRC) {
        for (SpreadsheetModel spreadsheetModel : sprModelsWithRC) {
            spreadsheetModel.getParameters()
                    .stream()
                    .filter(p -> p.getType().getType() == TypeInfo.Type.RUNTIMECONTEXT)
                    .findFirst()
                    .ifPresent(context -> {
                        spreadsheetModel.getParameters().remove(context);
                        spreadsheetModel.getPathInfo().setRuntimeContextParameter(context);
                    });
        }
    }

    private Set<String> fillCallsInSteps(final List<SpreadsheetParserModel> models,
                                         Set<String> datatypeRefs,
                                         Set<String> dataModelRefs,
                                         Set<String> lostDt) {
        Set<String> calledRefs = new HashSet<>();
        final Set<String> fixedDataTypes = Stream.concat(dataModelRefs.stream(), lostDt.stream())
                .collect(Collectors.toSet());
        // return type + spreadsheet name
        Set<Pair<String, String>> sprResultNames = new HashSet<>();
        for (SpreadsheetParserModel model : models) {
            String returnRef = model.getReturnRef();
            if (returnRef != null && model.isRefIsDataType() && models.stream()
                    .anyMatch(x -> returnRef.equals(x.getReturnRef()) && !x.isRefIsDataType()) && !fixedDataTypes
                    .contains(returnRef)) {
                datatypeRefs.remove(returnRef);
            }
        }
        final Set<String> datatypeNames = Stream.concat(datatypeRefs.stream(), fixedDataTypes.stream())
                .collect(Collectors.toSet())
                .stream()
                .map(ref -> OpenAPITypeUtils.getSimpleName(ref).toLowerCase())
                .collect(Collectors.toSet());

        Set<String> reservedWords = new HashSet<>(datatypeNames);
        Map<String, Set<String>> spreadsheetWithParameterNames = new HashMap<>();

        for (SpreadsheetParserModel model : models) {
            SpreadsheetModel spreadsheetModel = model.getModel();
            Set<String> parameterNames = spreadsheetModel.getParameters()
                    .stream()
                    .map(InputParameter::getFormattedName)
                    .collect(Collectors.toSet());
            String spreadsheetType = spreadsheetModel.getType();
            String returnRef = model.getReturnRef();
            final String spreadsheetName = spreadsheetModel.getName();
            PathInfo pathInfo = spreadsheetModel.getPathInfo();
            final String lowerCasedSpreadsheetName = spreadsheetName.toLowerCase();
            boolean spreadsheetWithSameNameAndParametersExists = spreadsheetWithParameterNames
                    .containsKey(lowerCasedSpreadsheetName) && spreadsheetWithParameterNames.get(lowerCasedSpreadsheetName)
                    .equals(parameterNames);
            if (spreadsheetWithSameNameAndParametersExists && returnRef == null) {
                String name = makeName(spreadsheetModel.getName(), reservedWords);
                spreadsheetModel.setName(name);
                pathInfo.setFormattedPath(name);
            } else if (returnRef != null && (SPREADSHEET_RESULT.equals(spreadsheetType) || !datatypeRefs
                    .contains(SCHEMAS_LINK + OpenAPITypeUtils.removeArrayBrackets(spreadsheetType)))) {
                TypeInfo returnType = pathInfo.getReturnType();
                if (returnType.getDimension() == 0 && (datatypeNames
                        .contains(lowerCasedSpreadsheetName) || spreadsheetWithSameNameAndParametersExists)) {
                    String modifiedName = findSpreadsheetName(returnRef, reservedWords);
                    spreadsheetModel.setName(modifiedName);
                    returnType.setJavaName(OpenAPITypeUtils.getSpreadsheetArrayClassName(returnType.getDimension()));
                    pathInfo.setFormattedPath(modifiedName);
                }
                sprResultNames.add(Pair.of(returnType.getSimpleName(), spreadsheetModel.getName()));
            }
            spreadsheetWithParameterNames.put(spreadsheetModel.getName().toLowerCase(), parameterNames);
            reservedWords.add(spreadsheetModel.getName().toLowerCase());
        }
        for (SpreadsheetParserModel parserModel : models) {
            SpreadsheetModel spreadsheetModel = parserModel.getModel();
            String refType = parserModel.getReturnRef() != null
                    ? OpenAPITypeUtils
                    .getSimpleName(parserModel.getReturnRef())
                    : "";
            Optional<Pair<String, String>> willBeCalled = sprResultNames.stream()
                    .filter(p -> p.getKey().equals(refType) && !p.getValue().equals(spreadsheetModel.getName()))
                    .findAny();
            PathInfo existingPathInfo = spreadsheetModel.getPathInfo();
            if (willBeCalled.isPresent()) {
                // change return type if the array of spreadsheets will be returned
                int dimension = existingPathInfo.getReturnType().getDimension();
                if (dimension > 0) {
                    spreadsheetModel.setType(SPREADSHEET_RESULT + willBeCalled.get().getValue() + String.join("",
                            Collections.nCopies(dimension, "[]")));
                    existingPathInfo.getReturnType()
                            .setJavaName(OpenAPITypeUtils.getSpreadsheetArrayClassName(dimension));
                }
            }
            for (StepModel step : spreadsheetModel.getSteps()) {
                String stepType = step.getType();
                boolean isArray = stepType.endsWith("[]");
                String type = OpenAPITypeUtils.removeArrayBrackets(step.getType());
                if (sprResultNames.stream().anyMatch(x -> x.getKey().equals(type))) {
                    Optional<SpreadsheetParserModel> foundSpr = Optional.empty();
                    if (willBeCalled.isPresent()) {
                        Pair<String, String> called = willBeCalled.get();
                        String calledType = called.getKey();
                        // if step type equals to the returned type of spreadsheet
                        if (type.equals(calledType)) {
                            foundSpr = models.stream()
                                    .filter(x -> x.getModel().getName().equals(called.getRight()))
                                    .findFirst();
                        }
                    }
                    // the called spreadsheet is not returned by the model
                    if (Objects.equals(foundSpr, Optional.empty())) {
                        foundSpr = models.stream().filter(sprModel -> {
                            boolean typesAreTheSame = sprModel.getReturnRef() != null && type
                                    .equals(OpenAPITypeUtils.getSimpleName(sprModel.getReturnRef()));
                            boolean notItSelf = !sprModel.getModel().getName().equals(spreadsheetModel.getName());
                            boolean isSpreadsheetResult = sprModel.getModel().getType().equals(SPREADSHEET_RESULT);
                            return typesAreTheSame && notItSelf && isSpreadsheetResult;
                        }).findAny();
                    }
                    // the called spreadsheet was found
                    if (foundSpr.isPresent()) {
                        SpreadsheetParserModel calledSpr = foundSpr.get();
                        String calledRef = calledSpr.getReturnRef();
                        calledRefs.add(calledRef);

                        SpreadsheetModel calledModel = calledSpr.getModel();
                        List<InputParameter> parameters = calledModel.getParameters();
                        String value = parameters.stream()
                                .map(InputParameter::getType)
                                .filter(t -> t.getType() != TypeInfo.Type.RUNTIMECONTEXT)
                                .map(OpenAPITypeUtils::getJavaDefaultValue)
                                .collect(Collectors.joining(", "));
                        String calledName = calledModel.getName();
                        String call = makeCall(calledName, value);
                        step.setValue(isArray ? makeArrayCall(stepType, calledName, call) : "= " + call);
                    }
                }
            }
        }
        return calledRefs;
    }

    private String findSpreadsheetName(final String returnRef, final Set<String> reservedNames) {
        String nameCandidate = OpenAPITypeUtils.getSimpleName(returnRef);
        return makeName(nameCandidate, reservedNames);
    }

    private String makeName(String candidate, final Set<String> reservedWords) {
        if (CollectionUtils.isNotEmpty(reservedWords) && reservedWords.contains(candidate.toLowerCase())) {
            candidate = candidate + "1";
            return makeName(candidate, reservedWords);
        }
        return candidate;
    }

    private String makeArrayCall(String stepType, String name, String call) {
        int dimension = calculateDimension(stepType);
        String openingBrackets = String.join("", Collections.nCopies(dimension, "{"));
        String closingBrackets = String.join("", Collections.nCopies(dimension, "}"));
        String arrayBrackets = String.join("", Collections.nCopies(dimension, "[]"));
        return new StringBuilder().append("= new SpreadsheetResult")
                .append(name)
                .append(arrayBrackets)
                .append(openingBrackets)
                .append(call)
                .append(closingBrackets)
                .toString();
    }

    private int calculateDimension(String stepType) {
        int count = 0;
        boolean brackets = false;
        for (int i = 0; i < stepType.length(); i++) {
            char c = stepType.charAt(i);
            if (c == '[') {
                if (!brackets) {
                    count++;
                }
                brackets = true;
            } else if (c == ']') {
                brackets = false;
            }
        }
        return count;
    }

    public boolean containsRuntimeContext(final Map<String, Integer> inputParametersEntry) {
        return inputParametersEntry != null && inputParametersEntry.containsKey(LINK_TO_DEFAULT_RUNTIME_CONTEXT);
    }

    public boolean containsOnlyRuntimeContext(final Collection<InputParameter> inputParameters) {
        return CollectionUtils.isNotEmpty(inputParameters) && inputParameters.size() == 1 && inputParameters.stream()
                .anyMatch(x -> x.getType().getType() == TypeInfo.Type.RUNTIMECONTEXT);
    }

    private List<SpreadsheetParserModel> extractSprModels(Paths paths,
                                                          OpenAPIRefResolver openAPIRefResolver,
                                                          Set<Pair<String, PathItem.HttpMethod>> pathWithPotentialSprResult,
                                                          Set<Pair<String, PathItem.HttpMethod>> pathsWithPrimitiveReturns,
                                                          Set<Pair<String, PathItem.HttpMethod>> pathsWithSpreadsheets,
                                                          Set<String> refsToExpand,
                                                          Set<String> childSet) {
        List<SpreadsheetParserModel> spreadSheetModels = new ArrayList<>();
        if (paths != null) {
            extractSpreadsheets(openAPIRefResolver,
                    pathWithPotentialSprResult,
                    refsToExpand,
                    spreadSheetModels,
                    paths,
                    PathType.SPREADSHEET_RESULT_PATH,
                    childSet);
            extractSpreadsheets(openAPIRefResolver,
                    pathsWithPrimitiveReturns,
                    refsToExpand,
                    spreadSheetModels,
                    paths,
                    PathType.SIMPLE_RETURN_PATH,
                    childSet);
            extractSpreadsheets(openAPIRefResolver,
                    pathsWithSpreadsheets,
                    refsToExpand,
                    spreadSheetModels,
                    paths,
                    PathType.SPREADSHEET_PATH,
                    childSet);
        }
        return spreadSheetModels;
    }

    private void extractSpreadsheets(OpenAPIRefResolver openAPIRefResolver,
                                     Set<Pair<String, PathItem.HttpMethod>> pathWithMethod,
                                     Set<String> refsToExpand,
                                     List<SpreadsheetParserModel> spreadSheetModels,
                                     Paths paths,
                                     PathType spreadsheetResultPath,
                                     Set<String> childSet) {
        final Map<String, Set<PathItem.HttpMethod>> pathWithOperationsMap = pathWithMethod.stream()
                .collect(Collectors.groupingBy(Pair::getKey, Collectors.mapping(Pair::getValue, Collectors.toSet())));

        for (Map.Entry<String, Set<PathItem.HttpMethod>> pathWithOperations : pathWithOperationsMap.entrySet()) {
            final String pathUrl = pathWithOperations.getKey();
            PathItem pathItem = paths.get(pathUrl);
            if (pathItem != null) {
                List<SpreadsheetParserModel> spr = extractSpreadsheetModel(openAPIRefResolver,
                        pathWithOperations.getValue(),
                        pathItem,
                        pathUrl,
                        refsToExpand,
                        spreadsheetResultPath,
                        childSet);
                spreadSheetModels.addAll(spr);
            }
        }
    }

    private List<SpreadsheetParserModel> extractSpreadsheetModel(OpenAPIRefResolver openAPIRefResolver,
                                                                 Set<PathItem.HttpMethod> methods,
                                                                 PathItem pathItem,
                                                                 String path,
                                                                 Set<String> refsToExpand,
                                                                 PathType pathType,
                                                                 Set<String> childSet) {
        List<SpreadsheetParserModel> spreadsheetParserModels = new ArrayList<>();
        boolean multipleOperations = methods.size() > 1;

        final Map<PathItem.HttpMethod, Operation> filteredMap = pathItem.readOperationsMap()
                .entrySet()
                .stream()
                .filter(m -> methods.contains(m.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry : filteredMap.entrySet()) {
            SpreadsheetParserModel spreadsheetParserModel = new SpreadsheetParserModel();
            SpreadsheetModel spr = new SpreadsheetModel();
            spreadsheetParserModel.setModel(spr);
            PathInfo pathInfo = generatePathInfo(path, operationEntry);
            spr.setPathInfo(pathInfo);
            Schema<?> responseSchema = OpenLOpenAPIUtils.getUsedSchemaInResponse(openAPIRefResolver,
                    operationEntry.getValue());
            if (responseSchema == null) {
                continue;
            }
            TypeInfo typeInfo = OpenAPITypeUtils.extractType(openAPIRefResolver, responseSchema, false);
            if (PathType.SPREADSHEET_RESULT_PATH.equals(pathType)) {
                typeInfo = new TypeInfo(SPREADSHEET_RESULT_CLASS_NAME,
                        typeInfo.getSimpleName(),
                        TypeInfo.Type.SPREADSHEET,
                        typeInfo.getDimension(),
                        typeInfo.isReference());
            }
            String usedSchemaInResponse = typeInfo.getSimpleName();
            pathInfo.setReturnType(typeInfo);
            boolean isChild = childSet.contains(usedSchemaInResponse);
            List<InputParameter> parameters = OpenLOpenAPIUtils
                    .extractParameters(openAPIRefResolver, refsToExpand, pathItem, operationEntry);
            String normalizedPath = replaceBrackets(path);
            String formattedName = generateSpreadsheetName(normalizedPath,
                    multipleOperations,
                    operationEntry.getKey().name());
            spr.setName(formattedName);
            spr.setParameters(parameters);
            pathInfo.setFormattedPath(formattedName);
            List<StepModel> stepModels = getStepModels(typeInfo,
                    openAPIRefResolver,
                    pathType,
                    spreadsheetParserModel,
                    spr,
                    responseSchema,
                    isChild);
            spr.setSteps(stepModels);
            spreadsheetParserModels.add(spreadsheetParserModel);
        }
        return spreadsheetParserModels;
    }

    private String generateSpreadsheetName(String normalizedPath, boolean multipleOperations, String operationName) {
        String potentialName = normalizeName(normalizedPath);
        if (multipleOperations) {
            potentialName += operationName;
        }
        return potentialName;
    }

    private PathInfo generatePathInfo(String path, Map.Entry<PathItem.HttpMethod, Operation> operationEntry) {
        PathInfo pathInfo = new PathInfo();
        final OperationInfo operationInfo = getOperationInfo(operationEntry.getValue(), operationEntry.getKey());
        pathInfo.setOriginalPath(path);
        pathInfo.setOperation(Optional.ofNullable(operationInfo.getMethod())
                .map(String::toUpperCase)
                .map(PathInfo.Operation::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("Invalid method operation")));
        pathInfo.setConsumes(operationInfo.getConsumes());
        pathInfo.setProduces(operationInfo.getProduces());
        return pathInfo;
    }

    private List<StepModel> getStepModels(TypeInfo typeInfo,
                                          OpenAPIRefResolver openAPIRefResolver,
                                          PathType pathType,
                                          SpreadsheetParserModel spreadsheetParserModel,
                                          SpreadsheetModel spr,
                                          Schema<?> usedSchemaInResponse,
                                          boolean isChild) {
        List<StepModel> stepModels = new ArrayList<>();
        boolean isArray = typeInfo.getDimension() > 0;
        String simpleName = typeInfo.getSimpleName();
        final String nameOfSchema = isArray ? OpenAPITypeUtils.removeArrayBrackets(simpleName) : simpleName;
        if (PathType.SPREADSHEET_RESULT_PATH == pathType) {
            Schema<?> schema = resolve(openAPIRefResolver, usedSchemaInResponse, Schema::get$ref);
            boolean isArrayOrChild = isArray || isChild;
            spr.setType(isArrayOrChild ? simpleName : SPREADSHEET_RESULT);
            if (schema != null) {
                if (isArrayOrChild) {
                    stepModels = makeSingleStep(simpleName);
                } else {
                    Map<String, Schema> properties = schema.getProperties();
                    if (CollectionUtils.isNotEmpty(properties)) {
                        stepModels = properties.entrySet()
                                .stream()
                                .filter(x -> !IGNORED_FIELDS.contains(x.getKey()))
                                .map(p -> extractStep(openAPIRefResolver, p))
                                .collect(Collectors.toList());
                    }
                }
                boolean addToDataTypes = stepModels.stream()
                        .anyMatch(x -> OpenAPITypeUtils.removeArrayBrackets(x.getType()).equals(nameOfSchema));
                spreadsheetParserModel.setStoreInModels(addToDataTypes || isArrayOrChild);
            }
            spreadsheetParserModel.setReturnRef(SCHEMAS_LINK + nameOfSchema);
        } else {
            spr.setType(simpleName);
            stepModels = makeSingleStep(simpleName);
        }
        return stepModels;
    }

    private List<StepModel> makeSingleStep(String stepType) {
        return Collections
                .singletonList(new StepModel(OpenAPIScaffoldingConverter.RESULT, stepType, makeValue(stepType)));
    }

    private OperationInfo getOperationInfo(Operation operation, PathItem.HttpMethod method) {
        String consumes = null;
        String produces = null;
        if (operation != null) {
            RequestBody requestBody = operation.getRequestBody();
            if (requestBody != null) {
                Content content = requestBody.getContent();
                if (CollectionUtils.isNotEmpty(content)) {
                    if (content.containsKey(APPLICATION_JSON)) {
                        consumes = APPLICATION_JSON;
                    } else if (content.containsKey(TEXT_PLAIN)) {
                        consumes = TEXT_PLAIN;
                    } else {
                        consumes = content.keySet().iterator().next();
                    }
                }
            }

            ApiResponses responses = operation.getResponses();
            ApiResponse successResponse = responses.get("200");
            ApiResponse defaultResponse = responses.getDefault();
            Content c = null;
            if (successResponse != null) {
                c = successResponse.getContent();
            } else if (defaultResponse != null) {
                c = defaultResponse.getContent();
            } else {
                if (CollectionUtils.isNotEmpty(responses)) {
                    ApiResponse firstResponse = responses.values().iterator().next();
                    c = firstResponse.getContent();
                }
            }
            if (CollectionUtils.isNotEmpty(c)) {
                if (c.containsKey(APPLICATION_JSON)) {
                    produces = APPLICATION_JSON;
                } else if (c.containsKey(TEXT_PLAIN)) {
                    produces = TEXT_PLAIN;
                } else {
                    produces = c.keySet().iterator().next();
                }
            }
        }
        return new OperationInfo(method.name(), produces, consumes);
    }

    private String replaceBrackets(String path) {
        return PARAMETERS_BRACKETS_MATCHER.matcher(path).replaceAll("");
    }

    private List<DatatypeModel> extractDataTypeModels(OpenAPIRefResolver openAPIRefResolver,
                                                      OpenAPI openAPI,
                                                      Set<String> allTheRefsWhichAreDataTypes) {
        List<DatatypeModel> result = new ArrayList<>();
        for (String datatypeRef : allTheRefsWhichAreDataTypes) {
            Schema<?> schema = (Schema<?>) OpenLOpenAPIUtils.resolveByRef(openAPIRefResolver, datatypeRef);
            if (schema != null && OpenAPITypeUtils.isComplexSchema(openAPIRefResolver, schema)) {
                DatatypeModel dm = createModel(openAPIRefResolver,
                        openAPI,
                        OpenAPITypeUtils.getSimpleName(datatypeRef),
                        schema);
                result.add(dm);
            }
        }
        return result;
    }

    private DatatypeModel createSimpleModel(String type) {
        DatatypeModel dm = new DatatypeModel("");
        dm.setFields(Collections.singletonList(new FieldModel("this", type)));
        return dm;
    }

    private DatatypeModel createModel(OpenAPIRefResolver openAPIRefResolver,
                                      OpenAPI openAPI,
                                      String schemaName,
                                      Schema<?> schema) {
        DatatypeModel dm = new DatatypeModel(normalizeName(schemaName));
        Map<String, Schema> properties;
        List<FieldModel> fields = new ArrayList<>();
        if (schema instanceof ComposedSchema) {
            ComposedSchema composedSchema = (ComposedSchema) schema;
            String parentName = OpenAPITypeUtils.getParentName(composedSchema, openAPI);
            properties = OpenAPITypeUtils.getFieldsOfChild(composedSchema);
            if (composedSchema.getProperties() != null) {
                composedSchema.getProperties().forEach(properties::putIfAbsent);
            }
            dm.setParent(parentName);
        } else {
            properties = schema.getProperties();
        }
        if (properties != null) {
            fields = properties.entrySet().stream().filter(property -> {
                boolean isIgnoredField = IGNORED_FIELDS.contains(property.getKey());
                String ref = property.getValue().get$ref();
                boolean isRuntimeContext = ref != null && ref.equals(LINK_TO_DEFAULT_RUNTIME_CONTEXT);
                return !(isIgnoredField || isRuntimeContext);
            }).map(p -> extractField(openAPIRefResolver, p)).collect(Collectors.toList());
        }
        dm.setFields(fields);
        return dm;
    }

    private DatatypeModel createModelForDataTable(OpenAPIRefResolver openAPIRefResolver,
                                                  OpenAPI openAPI,
                                                  String schemaName,
                                                  Schema<?> schema) {
        DatatypeModel dm = new DatatypeModel(normalizeName(schemaName));
        Map<String, Schema> properties;
        List<FieldModel> fields = new ArrayList<>();
        if (schema instanceof ComposedSchema) {
            properties = OpenAPITypeUtils.getAllProperties((ComposedSchema) schema, openAPI);
        } else {
            properties = schema.getProperties();
        }
        if (properties != null) {
            fields = properties.entrySet()
                    .stream()
                    .filter(property -> !IGNORED_FIELDS.contains(property.getKey()))
                    .map(p -> extractField(openAPIRefResolver, p))
                    .collect(Collectors.toList());
        }
        dm.setFields(fields);
        return dm;
    }

    private FieldModel extractField(OpenAPIRefResolver openAPIRefResolver, Map.Entry<String, Schema> property) {
        String propertyName = property.getKey();
        Schema<?> valueSchema = property.getValue();

        TypeInfo typeInfo = OpenAPITypeUtils.extractType(openAPIRefResolver, valueSchema, false);
        String typeModel = typeInfo.getSimpleName();
        Object defaultValue;
        if ((valueSchema instanceof IntegerSchema) && valueSchema.getFormat() == null) {
            if (valueSchema.getDefault() == null) {
                defaultValue = 0;
            } else {
                defaultValue = valueSchema.getDefault();
            }
        } else if (valueSchema instanceof NumberSchema && valueSchema.getFormat() == null && valueSchema
                .getDefault() != null) {
            defaultValue = valueSchema.getDefault().toString();
        } else {
            defaultValue = valueSchema.getDefault();
        }

        return new FieldModel(propertyName, typeModel, defaultValue);
    }

    private StepModel extractStep(OpenAPIRefResolver openAPIRefResolver, Map.Entry<String, Schema> property) {
        String propertyName = property.getKey();
        Schema<?> valueSchema = property.getValue();
        TypeInfo typeInfo = OpenAPITypeUtils.extractType(openAPIRefResolver, valueSchema, false);
        String typeModel = typeInfo.getSimpleName();
        String value = makeValue(typeModel);
        return new StepModel(normalizeName(propertyName), typeModel, value);
    }

    private String makeValue(String type) {
        String result = "";
        if (StringUtils.isNotBlank(type)) {
            if (OpenAPITypeUtils.isSimpleType(type)) {
                result = OpenAPITypeUtils.getSimpleValue(type);
            } else {
                result = createNewInstance(type);
            }
        }
        return result;
    }

    private String createNewInstance(String type) {
        StringBuilder result = new StringBuilder().append("= ").append("new ").append(type);
        if (type.endsWith("[]")) {
            result.append("{}");
        } else {
            result.append("()");
        }
        return result.toString();
    }

    private String makeCall(String type, String value) {
        return type + "(" + value + ")";
    }

    private String formatTableName(final String name) {
        String value = name.replaceFirst("^get", "");
        return name.equals(value) ? value : StringUtils.capitalize(value);
    }
}
