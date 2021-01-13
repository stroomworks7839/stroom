package stroom.rs.logging.impl;

import stroom.util.shared.HasId;
import stroom.util.shared.HasUuid;
import stroom.util.shared.StroomLog;
import stroom.util.shared.StroomLoggingOperationType;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ResourceInfo;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Collectors;

import static stroom.rs.logging.impl.StroomServerLoggingFilterImpl.LOGGER;

class RequestInfo {
    private final ContainerRequestContext requestContext;

    private final Object requestObj;
    private final ResourceInfo resourceInfo;
    private final StroomLoggingOperationType operationType;

    public RequestInfo(final ResourceInfo resourceInfo, final ContainerRequestContext requestContext) {
        this.resourceInfo = resourceInfo;
        this.requestContext = requestContext;
        this.requestObj = findRequestObj();
        this.operationType = findOperationType(getMethod(), getResourceClass(), requestContext.getMethod());
    }

    public RequestInfo(final ResourceInfo resourceInfo, final ContainerRequestContext requestContext, Object requestObj) {
        this.resourceInfo = resourceInfo;
        this.requestContext = requestContext;
        this.requestObj = requestObj;
        this.operationType = findOperationType(getMethod(), getResourceClass(), requestContext.getMethod());
    }

    public ContainerRequestContext getRequestContext() {
        return requestContext;
    }

    private Object findRequestObj(){
        Optional<String> paramNameOpt = requestContext.getUriInfo().getPathParameters(true).keySet().stream().findFirst();
        if (paramNameOpt.isEmpty()){
            return null;
        }

        String paramName = paramNameOpt.get();

        if (requestContext.getUriInfo().getPathParameters(true).keySet().size() > 1){
            LOGGER.warn("The request " + requestContext.getUriInfo().getPath(false) + " contains multiple parameters");
        }

        String paramValue = requestContext.getUriInfo().getPathParameters(true).get(paramName).stream().collect(Collectors.joining(", "));
        if ("id".equals(paramName)){
            return new ObjectId (paramValue);
        } else if ("uuid".equals(paramName)){
            return new ObjectUuid (paramValue);
        }

        return null;
    }

    public Object getRequestObj() {
        return requestObj;
    }

    public Class<?> getResourceClass() {
        return resourceInfo.getResourceClass();
    }

    public Method getMethod(){
        return resourceInfo.getResourceMethod();
    }

    public StroomLoggingOperationType getOperationType(){
        return operationType;
    }

    private static Optional<StroomLoggingOperationType> getOperationTypeFromAnnotations(final Method method,
                                                                                        final Class<?> resourceClass){
        if (method.getAnnotation(StroomLog.class) != null){
            return Optional.of(method.getAnnotation(StroomLog.class).value());
        } else if (resourceClass.getAnnotation(StroomLog.class) != null){
            return Optional.of(resourceClass.getAnnotation(StroomLog.class).value());
        }
        return Optional.empty();
    }

    public Optional<StroomLoggingOperationType> getOperationTypeFromAnnotations(){
        return getOperationTypeFromAnnotations(getMethod(), getResourceClass());
    }

    public String getTypeId(){
        //If method annotation provided use that on its own
        if ((getMethod().getAnnotation(StroomLog.class) != null) &&
                (!getMethod().getAnnotation(StroomLog.class).typeId().equals(StroomLog.ALLOCATE_AUTOMATICALLY))){
            return getMethod().getAnnotation(StroomLog.class).typeId();
        }
        String resourcePrefix = getResourceClass().getSimpleName();
        if ((getResourceClass().getAnnotation(StroomLog.class) != null) &&
                (!getResourceClass().getAnnotation(StroomLog.class).typeId().equals(StroomLog.ALLOCATE_AUTOMATICALLY))){
            resourcePrefix = getResourceClass().getAnnotation(StroomLog.class).typeId();
        }

        return resourcePrefix + "." + getMethod().getName();
    }

    public String getVerbFromAnnotations(){
        if ((getMethod().getAnnotation(StroomLog.class) != null) &&
                (!getMethod().getAnnotation(StroomLog.class).verb().equals(StroomLog.ALLOCATE_AUTOMATICALLY))){
            return getMethod().getAnnotation(StroomLog.class).verb();
        }
        return null;
    }


    private StroomLoggingOperationType findOperationType(final Method method,
                                                         final Class<?> resourceClass,
                                                         final String httpMethod) {
        Optional<StroomLoggingOperationType> type = getOperationTypeFromAnnotations(method, resourceClass);
        if (type.isPresent() && !StroomLoggingOperationType.ALLOCATE_AUTOMATICALLY.equals(type.get())){
            return type.get();
        } else if (HttpMethod.DELETE.equals(httpMethod)){
            return StroomLoggingOperationType.DELETE;
        } else if (method.getName().startsWith("get")){
            return StroomLoggingOperationType.VIEW;
        } else if (method.getName().startsWith("fetch")) {
            return StroomLoggingOperationType.VIEW;
        } else if (method.getName().startsWith("read")){
            return StroomLoggingOperationType.VIEW;
        } else if (method.getName().startsWith("create")){
            return StroomLoggingOperationType.CREATE;
        } else if (method.getName().startsWith("delete")){
            return StroomLoggingOperationType.DELETE;
        } else if (method.getName().startsWith("update")){
            return StroomLoggingOperationType.UPDATE;
        }  else if (method.getName().startsWith("save")){
            return StroomLoggingOperationType.UPDATE;
        } else if (method.getName().startsWith("find")){
            return StroomLoggingOperationType.SEARCH;
        } else if (method.getName().startsWith("search")){
            return StroomLoggingOperationType.SEARCH;
        }  else if (method.getName().startsWith("list")){
            return StroomLoggingOperationType.SEARCH;
        } else if (method.getName().startsWith("import")){
            return StroomLoggingOperationType.IMPORT;
        } else if (method.getName().startsWith("export")){
            return StroomLoggingOperationType.EXPORT;
        } else if (method.getName().startsWith("upload")){
            return StroomLoggingOperationType.IMPORT;
        } else if (method.getName().startsWith("download")){
            return StroomLoggingOperationType.EXPORT;
        } else if (method.getName().startsWith("set")){
            return StroomLoggingOperationType.UPDATE;
        } else if (method.getName().startsWith("copy")){
            return StroomLoggingOperationType.COPY;
        }
        return StroomLoggingOperationType.UNKNOWN;
    }

    private static class ObjectId implements HasId {
        private final long id;

        public ObjectId(String val){
            long id = 0;
            try {
                id = Long.parseLong(val);
            } catch (NumberFormatException ex) {
                LOGGER.error("Unable to log id of entity with non-numeric id " + val);
            } finally {
                this.id = id;
            }
        }

        @Override
        public long getId() {
            return id;
        }
    }

    private static class ObjectUuid implements HasUuid {
        private final String uuid;

        public ObjectUuid(String uuid){
            this.uuid = uuid;
        }

        @Override
        public String getUuid() {
            return uuid;
        }
    }


}
