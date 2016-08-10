/*
 * Copyright (C) 2016 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.sta;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import de.fraunhofer.iosb.ilt.sta.deserialize.EntityParser;
import de.fraunhofer.iosb.ilt.sta.model.core.Entity;
import de.fraunhofer.iosb.ilt.sta.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.sta.model.id.Id;
import de.fraunhofer.iosb.ilt.sta.model.id.LongId;
import de.fraunhofer.iosb.ilt.sta.mqtt.MqttManager;
import de.fraunhofer.iosb.ilt.sta.parser.path.PathParser;
import de.fraunhofer.iosb.ilt.sta.parser.query.QueryParser;
import de.fraunhofer.iosb.ilt.sta.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntitySetPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntityType;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePath;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePathElement;
import de.fraunhofer.iosb.ilt.sta.persistence.PersistenceManager;
import de.fraunhofer.iosb.ilt.sta.persistence.PersistenceManagerFactory;
import de.fraunhofer.iosb.ilt.sta.query.Query;
import de.fraunhofer.iosb.ilt.sta.serialize.EntityFormatter;
import de.fraunhofer.iosb.ilt.sta.util.IncompleteEntityException;
import de.fraunhofer.iosb.ilt.sta.util.NoSuchEntityException;
import de.fraunhofer.iosb.ilt.sta.util.UrlHelper;
import de.fraunhofer.iosb.ilt.sta.util.VisibilityHelper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
@WebListener
@WebServlet(
        name = "STA1.0",
        urlPatterns = {"/v1.0", "/v1.0/*"},
        initParams = {
            @WebInitParam(name = "readonly", value = "false")
        }
)
public class Servlet_1_0 extends HttpServlet implements ServletContextListener {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Servlet_1_0.class);
    private static final Charset ENCODING = Charset.forName("UTF-8");
    private static final String API_VERSION = "v1.0";
    private static final String USE_ABSOLUTE_NAVIGATION_LINKS_TAG = "useAbsoluteNavigationLinks";
    private static boolean useAbsoluteNavigationLinks;

    private static final String MQTT_CONFIG_TAG_IMPLEMENTATION_CLASS = "MqttImplementationClass";
    private static final String MQTT_CONFIG_TAG_ENABLED = "MqttEnabled";
    private static final String MQTT_CONFIG_TAG_QOS = "MqttQos";
    private static final String MQTT_CONFIG_TAG_PORT = "MqttPort";
    private static final String MQTT_CONFIG_TAG_HOST = "MqttHost";
    private static final String MQTT_CONFIG_TAG_WEBSOCKET_PORT = "MqttWebsocketPort";

    public static void sendError(HttpServletResponse response, int code, String message) throws IOException {
        Map<String, Object> map = new HashMap<>(2);
        map.put("code", code);
        map.put("message", message);
        String json = new EntityFormatter().writeObject(map);
        response.reset();
        response.setStatus(code);
        response.getWriter().write(json);
    }

    private Properties getDbProperties(ServletContext sc) {
        Properties props = new Properties();
        Enumeration<String> names = sc.getInitParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            props.put(name, sc.getInitParameter(name));
        }
        return props;
    }

    private PersistenceManager getPersistenceManager() {
        return PersistenceManagerFactory.getInstance().create();
    }

    private MqttSettings getMqttSettings(ServletContext sc) {
        MqttSettings settings = new MqttSettings(sc.getInitParameter(MQTT_CONFIG_TAG_IMPLEMENTATION_CLASS));
        settings.setTempPath(sc.getAttribute(ServletContext.TEMPDIR).toString());
        String enableMqtt = sc.getInitParameter(MQTT_CONFIG_TAG_ENABLED);
        if (enableMqtt != null) {
            settings.setEnableMqtt(Boolean.valueOf(enableMqtt));
        }
        String qos = sc.getInitParameter(MQTT_CONFIG_TAG_QOS);
        if (qos != null) {
            try {
                settings.setQosLevel(Integer.parseInt(qos));
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse mqtt qos value. Not a number: " + qos, e);
            }
        }
        String port = sc.getInitParameter(MQTT_CONFIG_TAG_PORT);
        if (port != null) {
            try {
                settings.setPort(Integer.parseInt(port));
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse mqtt port value. Not a number: " + port, e);
            }
        }
        String host = sc.getInitParameter(MQTT_CONFIG_TAG_HOST);
        if (host != null) {
            settings.setHost(host);
        }
        String websocketPort = sc.getInitParameter(MQTT_CONFIG_TAG_WEBSOCKET_PORT);
        if (websocketPort != null) {
            try {
                settings.setWebsocketPort(Integer.parseInt(websocketPort));
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse mqtt websocket port value. Not a number: " + websocketPort, e);
            }
        }
        settings.setTopicPrefix(API_VERSION + "/");
        return settings;
    }

    private Settings getSettings() {
        Settings settings = new Settings();
        ServletContext sc = getServletContext();
        String defaultCount = sc.getInitParameter("defaultCount");
        if (defaultCount != null) {
            settings.setCountDefault(Boolean.valueOf(defaultCount));
        }
        String defaulTop = sc.getInitParameter("defaultTop");
        if (defaulTop != null) {
            try {
                settings.setTopDefault(Integer.parseInt(defaulTop));
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse default top value. Not a number: " + defaulTop, e);
            }
        }
        String maxTop = sc.getInitParameter("maxTop");
        if (maxTop != null) {
            try {
                settings.setTopMax(Integer.parseInt(maxTop));
            } catch (NumberFormatException e) {
                LOGGER.error("Could not parse max top value. Not a number: " + maxTop, e);
            }
        }
        return settings;
    }

    /**
     * Processes requests for HTTP <code>GET</code> methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    private void processGetRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String pathInfo = request.getPathInfo();
        String queryInfo = request.getQueryString();
        URL serviceRootUrl = new URL(request.getScheme(), request.getLocalName(), request.getLocalPort(), request.getContextPath() + "/" + API_VERSION);
        String serviceRoot = serviceRootUrl.toExternalForm();

        PersistenceManager pm = null;
        try (PrintWriter out = response.getWriter()) {

            if (pathInfo == null || pathInfo.equals("/")) {
                Map<String, List<Map<String, String>>> capabilities = getCapabilities(request);
                String capabilitiesJsonString = new EntityFormatter().writeObject(capabilities);
                out.print(capabilitiesJsonString);
                return;
            }

            String pathString = URLDecoder.decode(pathInfo, ENCODING.name());
            ResourcePath path;
            try {
                path = PathParser.parsePath(serviceRoot, pathString);
            } catch (NumberFormatException e) {
                sendError(response, 404, "Not a valid id.");
                return;
            } catch (IllegalStateException e) {
                sendError(response, 404, "Not a valid path: " + e.getMessage());
                return;
            }
            String queryString;
            if (queryInfo == null) {
                queryString = null;
            } else {
                queryString = URLDecoder.decode(queryInfo, ENCODING.name());
            }
            Query query = null;
            try {
                query = QueryParser.parseQuery(queryString, getSettings());
            } catch (IllegalArgumentException e) {
                sendError(response, 400, "Invalid query: " + e.getMessage());
                return;
            }

            pm = getPersistenceManager();
            if (!pm.validatePath(path)) {
                sendError(response, 404, "Nothing found.");
                pm.commitAndClose();
                return;
            }
            Object object;
            try {
                object = pm.get(path, query);
            } catch (UnsupportedOperationException e) {
                LOGGER.error("Unsupported operation.", e);
                sendError(response, 500, "Unsupported operation: " + e.getMessage());
                pm.rollbackAndClose();
                return;
            } catch (IllegalArgumentException e) {
                LOGGER.error("Illegal operation.", e);
                sendError(response, 500, "Illegal operation: " + e.getMessage());
                pm.rollbackAndClose();
                return;
            }
            if (object == null) {
                sendError(response, 404, "Nothing found.");
                pm.commitAndClose();
                return;
            }
            String entityJsonString;
            if (Entity.class.isAssignableFrom(object.getClass())) {
                Entity entity = (Entity) object;
                VisibilityHelper.applyVisibility(entity, path, query, useAbsoluteNavigationLinks);
                entityJsonString = new EntityFormatter().writeEntity(entity);
            } else if (EntitySet.class.isAssignableFrom(object.getClass())) {
                EntitySet entitySet = (EntitySet) object;
                VisibilityHelper.applyVisibility(entitySet, path, query, useAbsoluteNavigationLinks);
                entityJsonString = new EntityFormatter().writeEntityCollection((EntitySet) object);
            } else if (path.isValue()) {
                if (object instanceof Map) {
                    entityJsonString = new EntityFormatter().writeObject(object);
                } else if (object instanceof Id) {
                    entityJsonString = ((Id) object).getValue().toString();
                } else {
                    entityJsonString = object.toString();
                }
            } else {
                entityJsonString = new EntityFormatter().writeObject(object);
            }

            out.println(entityJsonString);
            pm.commitAndClose();
        } catch (Exception e) {
            LOGGER.error("", e);
        } finally {
            if (pm != null) {
                pm.rollbackAndClose();
            }
        }
    }

    /**
     * Processes requests for HTTP <code>POST</code> methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    private void processPostRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String pathInfo = request.getPathInfo();
        URL serviceRootUrl = new URL(request.getScheme(), request.getLocalName(), request.getLocalPort(), request.getContextPath() + "/" + API_VERSION);
        String serviceRoot = serviceRootUrl.toExternalForm();

        PersistenceManager pm = null;
        PrintWriter out;
        try {
            out = response.getWriter();
            if (pathInfo == null || pathInfo.equals("/")) {
                sendError(response, 400, "POST only allowed to Collections.");
                return;
            }
            String pathString = URLDecoder.decode(pathInfo, ENCODING.name());
            ResourcePath path = PathParser.parsePath(serviceRoot, pathString);
            ResourcePathElement mainElement = path.getMainElement();
            if (!(mainElement instanceof EntitySetPathElement)) {
                sendError(response, 400, "POST only allowed to Collections.");
                return;
            }

            EntitySetPathElement mainSet = (EntitySetPathElement) mainElement;
            EntityType type = mainSet.getEntityType();
            EntityParser entityParser = new EntityParser(LongId.class);
            String data;
            try {
                data = readRequestData(request.getReader());
            } catch (IOException e) {
                LOGGER.error("Failed to read data.", e);
                sendError(response, 400, "Failed to read data.");
                return;
            }
            Entity entity;
            try {
                entity = entityParser.parseEntity(type.getImplementingClass(), data);
                entity.complete(mainSet);
            } catch (JsonMappingException | IncompleteEntityException | IllegalStateException ex) {
                LOGGER.debug("Post failed.", ex.getMessage());
                LOGGER.debug("Exception:", ex);
                sendError(response, 400, ex.getMessage());
                return;
            }

            pm = getPersistenceManager();
            try {

                if (pm.insert(entity)) {
                    pm.commitAndClose();
                    String url = UrlHelper.generateSelfLink(path, entity);
                    response.setStatus(201);
                    response.setHeader("location", url);
                    // send to MQTT
                    //MqttManager.getInstance().getServer().publish(url, new EntityFormatter().writeEntity(entity).getBytes(), 0);
                } else {
                    LOGGER.debug("Failed to insert entity.");
                    pm.rollbackAndClose();
                    sendError(response, 400, "Failed to insert entity.");
                }
            } catch (IncompleteEntityException | NoSuchEntityException e) {
                pm.rollbackAndClose();
                sendError(response, 400, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("", e);
            sendError(response, 500, e.getMessage());
        } finally {
            if (pm != null) {
                pm.rollbackAndClose();
            }
        }
    }

    private void processPatchRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String pathInfo = request.getPathInfo();
        URL serviceRootUrl = new URL(request.getScheme(), request.getLocalName(), request.getLocalPort(), request.getContextPath() + "/" + API_VERSION);
        String serviceRoot = serviceRootUrl.toExternalForm();

        PersistenceManager pm = null;
        PrintWriter out;
        try {
            out = response.getWriter();
            if (pathInfo == null || pathInfo.equals("/")) {
                sendError(response, 400, "PATCH only allowed on Entities.");
                return;
            }
            String pathString = URLDecoder.decode(pathInfo, ENCODING.name());
            ResourcePath path = PathParser.parsePath(serviceRoot, pathString);
            ResourcePathElement mainElement = path.getMainElement();
            if (!(mainElement instanceof EntityPathElement) || mainElement != path.getLastElement()) {
                sendError(response, 400, "PATCH only allowed on Entities.");
                return;
            }
            EntityPathElement mainEntity = (EntityPathElement) mainElement;
            if (mainEntity.getId() == null) {
                sendError(response, 400, "PATCH only allowed on Entities.");
                return;
            }

            EntityParser entityParser = new EntityParser(LongId.class);
            String data;
            try {
                data = readRequestData(request.getReader());
            } catch (IOException e) {
                LOGGER.error("Failed to read data.", e);
                sendError(response, 400, "Failed to read data.");
                return;
            }
            EntityType type = mainEntity.getEntityType();
            Entity entity;
            try {
                entity = entityParser.parseEntity(type.getImplementingClass(), data);
            } catch (JsonParseException | IncompleteEntityException e) {
                LOGGER.error("Could not parse json.", e);
                sendError(response, 400, "Could not parse json.");
                return;
            }

            pm = getPersistenceManager();
            try {

                if (pm.update(mainEntity, entity)) {
                    pm.commitAndClose();
                    response.setStatus(200);
                } else {
                    LOGGER.debug("Failed to update entity.");
                    pm.rollbackAndClose();
                }
            } catch (NoSuchEntityException e) {
                pm.rollbackAndClose();
                sendError(response, 400, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("", e);
            sendError(response, 500, e.getMessage());
        } finally {
            if (pm != null) {
                pm.rollbackAndClose();
            }
        }
    }

    private void processPutRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String pathInfo = request.getPathInfo();
        URL serviceRootUrl = new URL(request.getScheme(), request.getLocalName(), request.getLocalPort(), request.getContextPath() + "/" + API_VERSION);
        String serviceRoot = serviceRootUrl.toExternalForm();

        PersistenceManager pm = null;
        PrintWriter out;
        try {
            out = response.getWriter();
            if (pathInfo == null || pathInfo.equals("/")) {
                sendError(response, 400, "PUT only allowed on Entities.");
                return;
            }
            String pathString = URLDecoder.decode(pathInfo, ENCODING.name());
            ResourcePath path = PathParser.parsePath(serviceRoot, pathString);
            ResourcePathElement mainElement = path.getMainElement();
            if (!(mainElement instanceof EntityPathElement) || mainElement != path.getLastElement()) {
                sendError(response, 400, "PUT only allowed on Entities.");
                return;
            }
            EntityPathElement mainEntity = (EntityPathElement) mainElement;
            if (mainEntity.getId() == null) {
                sendError(response, 400, "PUT only allowed on Entities.");
                return;
            }

            EntityParser entityParser = new EntityParser(LongId.class);
            String data;
            try {
                data = readRequestData(request.getReader());
            } catch (IOException e) {
                LOGGER.error("Failed to read data.", e);
                sendError(response, 400, "Failed to read data.");
                return;
            }
            EntityType type = mainEntity.getEntityType();
            Entity entity;
            try {
                entity = entityParser.parseEntity(type.getImplementingClass(), data);
                entity.complete(true);
                entity.setEntityPropertiesSet();
            } catch (JsonParseException | IncompleteEntityException e) {
                LOGGER.error("Could not parse json.", e);
                sendError(response, 400, "Could not parse json.");
                return;
            }

            pm = getPersistenceManager();
            try {

                if (pm.update(mainEntity, entity)) {
                    pm.commitAndClose();
                    response.setStatus(200);
                } else {
                    LOGGER.debug("Failed to update entity.");
                    pm.rollbackAndClose();
                }
            } catch (NoSuchEntityException e) {
                pm.rollbackAndClose();
                sendError(response, 400, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("", e);
            sendError(response, 500, e.getMessage());
        } finally {
            if (pm != null) {
                pm.rollbackAndClose();
            }
        }
    }

    private void processDeleteRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String pathInfo = request.getPathInfo();
        URL serviceRootUrl = new URL(request.getScheme(), request.getLocalName(), request.getLocalPort(), request.getContextPath() + "/" + API_VERSION);
        String serviceRoot = serviceRootUrl.toExternalForm();

        PersistenceManager pm = null;
        try {
            if (pathInfo == null || pathInfo.equals("/")) {
                sendError(response, 400, "DELETE only allowed on Entities.");
                return;
            }
            String pathString = URLDecoder.decode(pathInfo, ENCODING.name());
            ResourcePath path = PathParser.parsePath(serviceRoot, pathString);
            ResourcePathElement mainElement = path.getMainElement();
            if (!(mainElement instanceof EntityPathElement)) {
                sendError(response, 400, "DELETE only allowed on Entities.");
                return;
            }
            if (mainElement != path.getLastElement()) {
                sendError(response, 400, "DELETE only allowed on Entities.");
                return;
            }
            EntityPathElement mainEntity = (EntityPathElement) mainElement;
            if (mainEntity.getId() == null) {
                sendError(response, 400, "DELETE only allowed on Entities.");
                return;
            }

            pm = getPersistenceManager();
            try {

                if (pm.delete(mainEntity)) {
                    pm.commitAndClose();
                    response.setStatus(200);
                } else {
                    LOGGER.debug("Failed to delete entity.");
                    pm.rollbackAndClose();
                }
            } catch (NoSuchEntityException e) {
                pm.rollbackAndClose();
                sendError(response, 404, e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("", e);
            sendError(response, 500, e.getMessage());
        } finally {
            if (pm != null) {
                pm.rollbackAndClose();
            }
        }
    }

    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processGetRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processPostRequest(request, response);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processPutRequest(request, response);
    }

    protected void doPatch(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processPatchRequest(request, response);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processDeleteRequest(request, response);
    }

    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "SensorThingsApi v1.0 servlet";
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if ("PATCH".equals(request.getMethod())) {
            doPatch(request, response);
            return;
        }
        super.service(request, response);
    }

    private Map<String, List<Map<String, String>>> getCapabilities(HttpServletRequest request) {
        Map<String, List<Map<String, String>>> result = new HashMap<>();
        List< Map<String, String>> capList = new ArrayList<>();
        result.put("value", capList);
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        try {
            URL servletUrl = new URL(scheme, serverName, serverPort, request.getContextPath() + "/");
            URL baseUrl = new URL(servletUrl, API_VERSION + "/");
            for (EntityType entityType : EntityType.values()) {
                capList.add(createCapability(entityType.plural, new URL(baseUrl, entityType.plural)));
            }
        } catch (MalformedURLException ex) {
            LOGGER.error("Failed to build url.", ex);
            return result;
        }
        return result;
    }

    private Map<String, String> createCapability(String name, URL url) {
        Map<String, String> val = new HashMap<>();
        val.put("name", name);
        val.put("url", url.toString());
        return Collections.unmodifiableMap(val);
    }

    private String readRequestData(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext context = sce.getServletContext();
        if (sce != null && context != null) {
            String serviceRootUrl = context.getInitParameter("serviceRootUrl") + context.getContextPath() + "/" + API_VERSION;
            if (context.getInitParameter(USE_ABSOLUTE_NAVIGATION_LINKS_TAG) != null) {
                useAbsoluteNavigationLinks = Boolean.parseBoolean(context.getInitParameter(USE_ABSOLUTE_NAVIGATION_LINKS_TAG)
                );
            }
            MqttManager.init(serviceRootUrl, getMqttSettings(context));
            PersistenceManagerFactory.init(getDbProperties(context), MqttManager.getInstance());
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        MqttManager.shutdown();
    }

}
