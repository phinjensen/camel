/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.xml;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import org.apache.camel.CamelContext;
import org.apache.camel.DelegateEndpoint;
import org.apache.camel.Endpoint;
import org.apache.camel.Expression;
import org.apache.camel.NamedNode;
import org.apache.camel.model.ExpressionNode;
import org.apache.camel.model.FromDefinition;
import org.apache.camel.model.OptionalIdentifiedDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RouteTemplateDefinition;
import org.apache.camel.model.RouteTemplatesDefinition;
import org.apache.camel.model.RoutesDefinition;
import org.apache.camel.model.SendDefinition;
import org.apache.camel.model.ToDynamicDefinition;
import org.apache.camel.model.language.ExpressionDefinition;
import org.apache.camel.spi.ModelToXMLDumper;
import org.apache.camel.spi.NamespaceAware;
import org.apache.camel.spi.annotations.JdkService;
import org.apache.camel.util.KeyValueHolder;
import org.apache.camel.xml.out.ModelWriter;

import static org.apache.camel.model.ProcessorDefinitionHelper.filterTypeInOutputs;

/**
 * Lightweight {@link ModelToXMLDumper} based on the generated {@link ModelWriter}.
 */
@JdkService(ModelToXMLDumper.FACTORY)
public class LwModelToXMLDumper implements ModelToXMLDumper {

    @Override
    public String dumpModelAsXml(CamelContext context, NamedNode definition) throws Exception {
        return dumpModelAsXml(context, definition, false, false);
    }

    @Override
    public String dumpModelAsXml(
            CamelContext context, NamedNode definition, boolean resolvePlaceholders, boolean resolveDelegateEndpoints)
            throws Exception {

        Properties properties = new Properties();
        Map<String, String> namespaces = new LinkedHashMap<>();
        Map<String, KeyValueHolder<Integer, String>> locations = new HashMap<>();
        Consumer<RouteDefinition> extractor = route -> {
            extractNamespaces(route, namespaces);
            if (context.isDebugging()) {
                extractSourceLocations(route, locations);
            }
            resolveEndpointDslUris(route);
            if (Boolean.TRUE.equals(route.isTemplate())) {
                Map<String, Object> parameters = route.getTemplateParameters();
                if (parameters != null) {
                    properties.putAll(parameters);
                }
            }
        };

        StringWriter buffer = new StringWriter();
        ModelWriter writer = new ModelWriter(buffer, "http://camel.apache.org/schema/spring") {
            @Override
            protected void doWriteOptionalIdentifiedDefinitionAttributes(OptionalIdentifiedDefinition<?> def)
                    throws IOException {
                // write customId if not false
                if (Boolean.TRUE.equals(def.getCustomId())) {
                    doWriteAttribute("customId", toString(def.getCustomId()));
                }
                // write id
                doWriteAttribute("id", def.getId());
                // write location information
                if (context.isDebugging()) {
                    String loc = (def instanceof RouteDefinition ? ((RouteDefinition) def).getInput() : def).getLocation();
                    int line = (def instanceof RouteDefinition ? ((RouteDefinition) def).getInput() : def).getLineNumber();
                    if (line != -1) {
                        writer.addAttribute("sourceLineNumber", Integer.toString(line));
                        writer.addAttribute("sourceLocation", loc);
                    }
                }
            }

            @Override
            protected void startElement(String name) throws IOException {
                boolean namespaceWritten = this.namespaceWritten;
                super.startElement(name);
                if (!namespaceWritten) {
                    for (Map.Entry<String, String> entry : namespaces.entrySet()) {
                        // TODO: check duplicate xmlns="xxx" attribute ?
                        String nsPrefix = entry.getKey();
                        String prefix = nsPrefix.equals("xmlns") ? nsPrefix : "xmlns:" + nsPrefix;
                        writer.addAttribute(prefix, entry.getValue());
                    }
                }
            }

            @Override
            protected void doWriteValue(String value) throws IOException {
                if (value != null && !value.isEmpty()) {
                    super.doWriteValue(value);
                }
            }

            @Override
            protected void text(String text) throws IOException {
                if (resolvePlaceholders) {
                    text = resolve(text, properties);
                }
                super.text(text);
            }

            @Override
            protected void attribute(String name, String value) throws IOException {
                if (resolveDelegateEndpoints && "uri".equals(name)) {
                    String uri = resolve(value, properties);
                    Endpoint endpoint = context.hasEndpoint(uri);
                    if (endpoint instanceof DelegateEndpoint) {
                        endpoint = ((DelegateEndpoint) endpoint).getEndpoint();
                        value = endpoint.getEndpointUri();
                    }
                }
                if (resolvePlaceholders) {
                    value = resolve(value, properties);
                }
                super.attribute(name, value);
            }

            String resolve(String value, Properties properties) {
                context.getPropertiesComponent().setLocalProperties(properties);
                try {
                    return context.resolvePropertyPlaceholders(value);
                } catch (Exception e) {
                    return value;
                } finally {
                    // clear local after the route is dumped
                    context.getPropertiesComponent().setLocalProperties(null);
                }
            }
        };

        // gather all namespaces from the routes or route which is stored on the expression nodes
        if (definition instanceof RouteTemplatesDefinition templates) {
            templates.getRouteTemplates().forEach(template -> extractor.accept(template.getRoute()));
        } else if (definition instanceof RouteTemplateDefinition template) {
            extractor.accept(template.getRoute());
        } else if (definition instanceof RoutesDefinition routes) {
            routes.getRoutes().forEach(extractor);
        } else if (definition instanceof RouteDefinition route) {
            extractor.accept(route);
        }
        writer.writeOptionalIdentifiedDefinitionRef((OptionalIdentifiedDefinition) definition);

        return buffer.toString();
    }

    /**
     * Extract all XML namespaces from the expressions in the route
     *
     * @param route      the route
     * @param namespaces the map of namespaces to add discovered XML namespaces into
     */
    private static void extractNamespaces(RouteDefinition route, Map<String, String> namespaces) {
        Collection<ExpressionNode> col = filterTypeInOutputs(route.getOutputs(), ExpressionNode.class);
        for (ExpressionNode en : col) {
            NamespaceAware na = getNamespaceAwareFromExpression(en);
            if (na != null) {
                Map<String, String> map = na.getNamespaces();
                if (map != null && !map.isEmpty()) {
                    namespaces.putAll(map);
                }
            }
        }
    }

    /**
     * Extract all source locations from the route
     *
     * @param route     the route
     * @param locations the map of source locations for EIPs in the route
     */
    private static void extractSourceLocations(RouteDefinition route, Map<String, KeyValueHolder<Integer, String>> locations) {
        // input
        String id = route.getRouteId();
        String loc = route.getInput().getLocation();
        int line = route.getInput().getLineNumber();
        if (id != null && line != -1) {
            locations.put(id, new KeyValueHolder<>(line, loc));
        }
        // and then walk all nodes in the route graphs
        for (var def : filterTypeInOutputs(route.getOutputs(), OptionalIdentifiedDefinition.class)) {
            id = def.getId();
            loc = def.getLocation();
            line = def.getLineNumber();
            if (id != null && line != -1) {
                locations.put(id, new KeyValueHolder<>(line, loc));
            }
        }
    }

    /**
     * If the route has been built with endpoint-dsl, then the model will not have uri set which then cannot be included
     * in the JAXB model dump
     */
    @SuppressWarnings("rawtypes")
    private static void resolveEndpointDslUris(RouteDefinition route) {
        FromDefinition from = route.getInput();
        if (from != null && from.getEndpointConsumerBuilder() != null) {
            String uri = from.getEndpointConsumerBuilder().getRawUri();
            from.setUri(uri);
        }
        Collection<SendDefinition> col = filterTypeInOutputs(route.getOutputs(), SendDefinition.class);
        for (SendDefinition<?> to : col) {
            if (to.getEndpointProducerBuilder() != null) {
                String uri = to.getEndpointProducerBuilder().getRawUri();
                to.setUri(uri);
            }
        }
        Collection<ToDynamicDefinition> col2 = filterTypeInOutputs(route.getOutputs(), ToDynamicDefinition.class);
        for (ToDynamicDefinition to : col2) {
            if (to.getEndpointProducerBuilder() != null) {
                String uri = to.getEndpointProducerBuilder().getRawUri();
                to.setUri(uri);
            }
        }
    }

    private static NamespaceAware getNamespaceAwareFromExpression(ExpressionNode expressionNode) {
        ExpressionDefinition ed = expressionNode.getExpression();

        NamespaceAware na = null;
        Expression exp = ed.getExpressionValue();
        if (exp instanceof NamespaceAware) {
            na = (NamespaceAware) exp;
        } else if (ed instanceof NamespaceAware) {
            na = (NamespaceAware) ed;
        }

        return na;
    }

}
